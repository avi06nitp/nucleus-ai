package com.visa.nucleus.api;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.service.OrchestratorService;
import com.visa.nucleus.core.service.SessionManager;
import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SessionControllerTest {

    @Mock private OrchestratorService orchestratorService;
    @Mock private SessionManager sessionManager;
    @Mock private AgentPlugin agentPlugin;
    @Mock private RuntimePlugin runtimePlugin;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private SessionController controller;

    @BeforeEach
    void setUp() {
        controller = new SessionController(
                orchestratorService, sessionManager, agentPlugin, runtimePlugin, messagingTemplate);
    }

    // ------------------------------------------------------------------
    // GET /api/sessions
    // ------------------------------------------------------------------

    @Test
    void listSessions_returnsAllSessions() {
        AgentSession s1 = new AgentSession("proj-a", "PROJ-1");
        AgentSession s2 = new AgentSession("proj-b", "PROJ-2");
        when(sessionManager.getAllSessions()).thenReturn(List.of(s1, s2));

        List<AgentSession> result = controller.listSessions();

        assertEquals(2, result.size());
    }

    // ------------------------------------------------------------------
    // GET /api/sessions/{id}
    // ------------------------------------------------------------------

    @Test
    void getSession_returnsSessionWhenFound() {
        AgentSession session = new AgentSession("proj", "PROJ-10");
        when(sessionManager.getSession(session.getSessionId())).thenReturn(Optional.of(session));

        ResponseEntity<AgentSession> response = controller.getSession(session.getSessionId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(session.getSessionId(), response.getBody().getSessionId());
    }

    @Test
    void getSession_returns404WhenNotFound() {
        when(sessionManager.getSession("missing")).thenReturn(Optional.empty());

        ResponseEntity<AgentSession> response = controller.getSession("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ------------------------------------------------------------------
    // POST /api/sessions/spawn
    // ------------------------------------------------------------------

    @Test
    void spawnSession_createsSessionAndBroadcasts() throws Exception {
        AgentSession session = new AgentSession("proj", "PROJ-42");
        when(orchestratorService.spawn("proj", "PROJ-42")).thenReturn(session);

        ResponseEntity<AgentSession> response = controller.spawnSession(
                new SessionController.SpawnRequest("proj", "PROJ-42", "claude"));

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(session.getSessionId(), response.getBody().getSessionId());

        verify(orchestratorService).spawn("proj", "PROJ-42");
        verify(messagingTemplate).convertAndSend(eq("/topic/sessions"), eq(session));
    }

    @Test
    void spawnSession_propagatesExceptionFromOrchestrator() throws Exception {
        when(orchestratorService.spawn(any(), any()))
                .thenThrow(new RuntimeException("spawn failed"));

        assertThrows(RuntimeException.class,
                () -> controller.spawnSession(
                        new SessionController.SpawnRequest("proj", "PROJ-1", "claude")));
    }

    // ------------------------------------------------------------------
    // DELETE /api/sessions/{id}
    // ------------------------------------------------------------------

    @Test
    void terminateSession_callsOrchestratorAndReturns204() throws Exception {
        when(sessionManager.getSession("abc")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.terminateSession("abc");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(orchestratorService).terminate("abc");
    }

    @Test
    void terminateSession_broadcastsIfSessionExists() throws Exception {
        AgentSession session = new AgentSession("proj", "PROJ-1");
        session.setStatus(AgentSession.Status.COMPLETED);
        when(sessionManager.getSession(session.getSessionId())).thenReturn(Optional.of(session));

        controller.terminateSession(session.getSessionId());

        verify(messagingTemplate).convertAndSend(eq("/topic/sessions"), eq(session));
    }

    // ------------------------------------------------------------------
    // POST /api/sessions/{id}/message
    // ------------------------------------------------------------------

    @Test
    void sendMessage_forwardsToAgentPlugin() throws Exception {
        AgentSession session = new AgentSession("proj", "PROJ-1");
        when(sessionManager.getSession(session.getSessionId())).thenReturn(Optional.of(session));

        ResponseEntity<Void> response = controller.sendMessage(
                session.getSessionId(), new SessionController.MessageRequest("please add tests"));

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(agentPlugin).sendMessage(session.getSessionId(), "please add tests");
    }

    @Test
    void sendMessage_returns404ForUnknownSession() throws Exception {
        when(sessionManager.getSession("missing")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.sendMessage(
                "missing", new SessionController.MessageRequest("hi"));

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(agentPlugin);
    }

    // ------------------------------------------------------------------
    // GET /api/sessions/{id}/logs
    // ------------------------------------------------------------------

    @Test
    void getLogs_returnsLogsFromRuntimePlugin() throws Exception {
        AgentSession session = new AgentSession("proj", "PROJ-1");
        when(sessionManager.getSession(session.getSessionId())).thenReturn(Optional.of(session));
        when(runtimePlugin.getLogs(session.getSessionId())).thenReturn("line1\nline2\n");

        ResponseEntity<String> response = controller.getLogs(session.getSessionId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("line1\nline2\n", response.getBody());
    }

    @Test
    void getLogs_returns404ForUnknownSession() throws Exception {
        when(sessionManager.getSession("missing")).thenReturn(Optional.empty());

        ResponseEntity<String> response = controller.getLogs("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verifyNoInteractions(runtimePlugin);
    }

    // ------------------------------------------------------------------
    // POST /api/sessions/{id}/restore
    // ------------------------------------------------------------------

    @Test
    void restoreSession_returnsRestoredSessionAndBroadcasts() throws Exception {
        AgentSession session = new AgentSession("proj", "PROJ-99");
        session.setStatus(AgentSession.Status.RUNNING);
        when(orchestratorService.restore(session.getSessionId())).thenReturn(session);

        ResponseEntity<AgentSession> response = controller.restoreSession(session.getSessionId());

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals(session.getSessionId(), response.getBody().getSessionId());

        verify(orchestratorService).restore(session.getSessionId());
        verify(messagingTemplate).convertAndSend(eq("/topic/sessions"), eq(session));
    }

    @Test
    void restoreSession_propagatesExceptionFromOrchestrator() throws Exception {
        when(orchestratorService.restore("bad-id"))
                .thenThrow(new IllegalArgumentException("Session not found: bad-id"));

        assertThrows(IllegalArgumentException.class, () -> controller.restoreSession("bad-id"));
    }
}
