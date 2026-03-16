package com.visa.nucleus.api;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.service.OrchestratorService;
import com.visa.nucleus.core.service.SessionManager;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing agent sessions. Broadcasts session-state changes
 * over WebSocket to all connected dashboard clients.
 *
 * <p>Agent and runtime plugins are resolved per-session via the OrchestratorService
 * so that multi-project configurations with different agent types and runtimes
 * are handled transparently.</p>
 */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final OrchestratorService orchestratorService;
    private final SessionManager sessionManager;
    private final SimpMessagingTemplate messagingTemplate;

    public SessionController(OrchestratorService orchestratorService,
                             SessionManager sessionManager,
                             SimpMessagingTemplate messagingTemplate) {
        this.orchestratorService = orchestratorService;
        this.sessionManager = sessionManager;
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * GET /api/sessions
     * Returns all sessions, which the dashboard uses to populate the Kanban board.
     */
    @GetMapping
    public List<AgentSession> listSessions() {
        return sessionManager.getAllSessions();
    }

    /**
     * GET /api/sessions/{id}
     * Returns a single session with full details.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AgentSession> getSession(@PathVariable String id) {
        return sessionManager.getSession(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * POST /api/sessions/spawn
     * Body: { projectName, ticketId, agentType }
     * Creates a new agent session via the orchestrator.
     */
    @PostMapping("/spawn")
    public ResponseEntity<AgentSession> spawnSession(@RequestBody SpawnRequest request) throws Exception {
        AgentSession session = orchestratorService.spawn(request.projectName(), request.ticketId());
        broadcast(session);
        return ResponseEntity.status(HttpStatus.CREATED).body(session);
    }

    /**
     * DELETE /api/sessions/{id}
     * Terminates a running session.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> terminateSession(@PathVariable String id) throws Exception {
        orchestratorService.terminate(id);
        sessionManager.getSession(id).ifPresent(this::broadcast);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/sessions/{id}/restore
     * Restores a FAILED session: recreates the worktree if missing, restarts the
     * runtime, and re-initializes the agent with fresh issue context.
     */
    @PostMapping("/{id}/restore")
    public ResponseEntity<AgentSession> restoreSession(@PathVariable String id) throws Exception {
        AgentSession session = orchestratorService.restore(id);
        broadcast(session);
        return ResponseEntity.ok(session);
    }

    /**
     * POST /api/sessions/{id}/message
     * Body: { message }
     * Lets engineers manually send instructions to a running agent.
     * Routes to the correct AgentPlugin based on the session's agent type.
     */
    @PostMapping("/{id}/message")
    public ResponseEntity<Void> sendMessage(@PathVariable String id,
                                            @RequestBody MessageRequest request) throws Exception {
        if (sessionManager.getSession(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        orchestratorService.agentPluginForSession(id).sendMessage(id, request.message());
        return ResponseEntity.ok().build();
    }

    /**
     * GET /api/sessions/{id}/logs
     * Returns the runtime logs for a session.
     * Routes to the correct RuntimePlugin based on the session's project runtime.
     */
    @GetMapping("/{id}/logs")
    public ResponseEntity<String> getLogs(@PathVariable String id) throws Exception {
        if (sessionManager.getSession(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String agentLogs = orchestratorService.agentPluginForSession(id).getLogs(id);
        String runtimeLogs = orchestratorService.runtimePluginForSession(id).getLogs(id);
        String combined = agentLogs.isBlank() ? runtimeLogs
                : runtimeLogs.isBlank() ? agentLogs
                : agentLogs + "\n\n--- Runtime Output ---\n" + runtimeLogs;
        return ResponseEntity.ok(combined);
    }

    // -------------------------------------------------------------------------
    // WebSocket broadcast helper
    // -------------------------------------------------------------------------

    private void broadcast(AgentSession session) {
        messagingTemplate.convertAndSend("/topic/sessions", session);
    }

    // -------------------------------------------------------------------------
    // Request DTOs (Java 16+ records)
    // -------------------------------------------------------------------------

    public record SpawnRequest(String projectName, String ticketId, String agentType) {}

    public record MessageRequest(String message) {}
}
