package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import com.visa.nucleus.plugins.agent.AgentPluginFactory;
import com.visa.nucleus.plugins.runtime.RuntimePluginFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrchestratorServiceTest {

    @Mock TrackerPlugin trackerPlugin;
    @Mock WorkspacePlugin workspacePlugin;
    @Mock RuntimePlugin runtimePlugin;
    @Mock AgentPlugin agentPlugin;
    @Mock NotifierPlugin notifierPlugin;
    @Mock AgentSessionRepository sessionRepository;
    @Mock AgentPluginFactory agentPluginFactory;
    @Mock RuntimePluginFactory runtimePluginFactory;
    @Mock ProjectService projectService;
    @Mock NucleusProperties nucleusProperties;

    private OrchestratorService orchestrator;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        Map<String, AgentSession> store = new HashMap<>();
        when(sessionRepository.save(any(AgentSession.class))).thenAnswer(inv -> {
            AgentSession s = inv.getArgument(0);
            store.put(s.getSessionId(), s);
            return s;
        });
        when(sessionRepository.findById(anyString())).thenAnswer(inv ->
                Optional.ofNullable(store.get((String) inv.getArgument(0))));

        NucleusProperties.Defaults defaults = new NucleusProperties.Defaults();
        when(nucleusProperties.getDefaults()).thenReturn(defaults);
        when(nucleusProperties.getReactions()).thenReturn(new HashMap<>());

        when(agentPluginFactory.create(anyString())).thenReturn(agentPlugin);
        when(runtimePluginFactory.create(anyString())).thenReturn(runtimePlugin);

        sessionManager = new SessionManager(sessionRepository);
        orchestrator = new OrchestratorService(
                sessionManager, projectService, trackerPlugin, workspacePlugin,
                agentPluginFactory, runtimePluginFactory, List.of(notifierPlugin),
                nucleusProperties);
    }

    @Test
    void spawn_creates_running_session() throws Exception {
        when(projectService.getProject("my-project")).thenReturn(Optional.of(project("my-project")));
        when(trackerPlugin.getIssueContext("T-1")).thenReturn("Issue context");
        when(workspacePlugin.generateBranchName("T-1", "my-project")).thenReturn("feat/T-1-my-project");
        when(workspacePlugin.createWorktree("/repo", "feat/T-1-my-project")).thenReturn("/tmp/worktrees/feat/T-1-my-project");

        AgentSession session = orchestrator.spawn("my-project", "T-1");

        assertEquals(AgentSession.Status.RUNNING, session.getStatus());
        assertEquals("/tmp/worktrees/feat/T-1-my-project", session.getWorktreePath());
        assertEquals("T-1", session.getTicketId());

        verify(runtimePlugin).start(session);
        verify(agentPlugin).initialize(session, "Issue context");
    }

    @Test
    void spawn_saves_session_to_repository() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/worktrees/feat/branch");

        AgentSession session = orchestrator.spawn("proj", "T-2");

        assertTrue(sessionManager.getSession(session.getSessionId()).isPresent());
    }

    @Test
    void spawn_throws_for_unknown_project() {
        when(projectService.getProject("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> orchestrator.spawn("unknown", "T-X"));
    }

    @Test
    void terminate_stops_runtime_and_deletes_worktree() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt/feat/branch");

        AgentSession session = orchestrator.spawn("proj", "T-3");

        orchestrator.terminate(session.getSessionId());

        verify(runtimePlugin).stop(session.getSessionId());
        verify(workspacePlugin).deleteWorktree("/tmp/wt/feat/branch");
        assertEquals(AgentSession.Status.FAILED, sessionManager.getSession(session.getSessionId()).get().getStatus());
    }

    @Test
    void terminate_throws_for_unknown_session() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.terminate("unknown-id"));
    }

    @Test
    void handleCiFailure_sends_message_and_increments_count() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt");

        AgentSession session = orchestrator.spawn("proj", "T-4");

        orchestrator.handleCiFailure(session.getSessionId(), "Build error at line 42");

        verify(agentPlugin).sendMessage(eq(session.getSessionId()), contains("CI failed"));
        assertEquals(1, sessionManager.getSession(session.getSessionId()).get().getCiRetryCount());
    }

    @Test
    void handleCiFailure_notifies_after_max_retries() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt");

        AgentSession session = orchestrator.spawn("proj", "T-5");

        // Trigger 4 CI failures (exceeds MAX_CI_RETRIES = 3)
        for (int i = 0; i < 4; i++) {
            orchestrator.handleCiFailure(session.getSessionId(), "logs");
        }

        verify(notifierPlugin, atLeastOnce()).notify(
                eq(session.getSessionId()), anyString(), eq(NotificationLevel.NEEDS_ATTENTION));
    }

    @Test
    void handleCiFailure_does_not_notify_below_threshold() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt");

        AgentSession session = orchestrator.spawn("proj", "T-6");

        orchestrator.handleCiFailure(session.getSessionId(), "logs");
        orchestrator.handleCiFailure(session.getSessionId(), "logs");
        orchestrator.handleCiFailure(session.getSessionId(), "logs");

        verify(notifierPlugin, never()).notify(anyString(), anyString(), any());
    }

    @Test
    void handleReviewComment_forwards_comment_and_sets_running() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt");

        AgentSession session = orchestrator.spawn("proj", "T-7");

        orchestrator.handleReviewComment(session.getSessionId(), "Please fix the null check.");

        verify(agentPlugin).sendMessage(eq(session.getSessionId()), contains("Reviewer says: Please fix the null check."));
        assertEquals(AgentSession.Status.RUNNING, sessionManager.getSession(session.getSessionId()).get().getStatus());
    }

    @Test
    void handleReviewComment_throws_for_unknown_session() {
        assertThrows(IllegalArgumentException.class,
                () -> orchestrator.handleReviewComment("unknown-id", "comment"));
    }

    // ------------------------------------------------------------------
    // restore
    // ------------------------------------------------------------------

    @Test
    void restore_recreates_worktree_and_restarts_agent() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        AgentSession session = new AgentSession("proj", "T-10");
        session.setStatus(AgentSession.Status.FAILED);
        session.setBranchName("feat/branch");
        session.setWorktreePath(null); // worktree is gone
        sessionManager.save(session);

        when(trackerPlugin.getIssueContext("T-10")).thenReturn("fresh context");
        when(workspacePlugin.restoreWorktree("/repo", "feat/branch")).thenReturn("/tmp/wt/feat/branch");

        AgentSession restored = orchestrator.restore(session.getSessionId());

        assertEquals(AgentSession.Status.RUNNING, restored.getStatus());
        assertEquals("feat/branch", restored.getBranchName());
        verify(workspacePlugin).restoreWorktree("/repo", "feat/branch");
        verify(runtimePlugin).start(restored);
        verify(agentPlugin).initialize(restored, "fresh context");
    }

    @Test
    void restore_throws_for_unknown_session() {
        assertThrows(IllegalArgumentException.class, () -> orchestrator.restore("no-such-id"));
    }

    @Test
    void restore_throws_for_session_in_non_restoreable_state() throws Exception {
        when(projectService.getProject("proj")).thenReturn(Optional.of(project("proj")));
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/wt");

        AgentSession session = orchestrator.spawn("proj", "T-12");
        session.setStatus(AgentSession.Status.MERGED);
        sessionManager.save(session);

        assertThrows(IllegalStateException.class, () -> orchestrator.restore(session.getSessionId()));
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Project project(String name) {
        Project p = new Project();
        p.setName(name);
        p.setPath("/repo");
        p.setAgentType("claude");
        p.setRuntime("docker");
        return p;
    }
}
