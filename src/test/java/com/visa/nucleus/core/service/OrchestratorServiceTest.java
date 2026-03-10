package com.visa.nucleus.core.service;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OrchestratorServiceTest {

    @Mock TrackerPlugin trackerPlugin;
    @Mock WorkspacePlugin workspacePlugin;
    @Mock RuntimePlugin runtimePlugin;
    @Mock AgentPlugin agentPlugin;
    @Mock NotifierPlugin notifierPlugin;

    private OrchestratorService orchestrator;
    private SessionManager sessionManager;

    @BeforeEach
    void setUp() {
        sessionManager = new SessionManager(new AgentSessionRepository());
        orchestrator = new OrchestratorService(
                sessionManager, trackerPlugin, workspacePlugin,
                runtimePlugin, agentPlugin, notifierPlugin, "/repo");
    }

    @Test
    void spawn_creates_running_session() throws Exception {
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
        when(trackerPlugin.getIssueContext(anyString())).thenReturn("ctx");
        when(workspacePlugin.generateBranchName(anyString(), anyString())).thenReturn("feat/branch");
        when(workspacePlugin.createWorktree(anyString(), anyString())).thenReturn("/tmp/worktrees/feat/branch");

        AgentSession session = orchestrator.spawn("proj", "T-2");

        assertTrue(sessionManager.getSession(session.getSessionId()).isPresent());
    }

    @Test
    void terminate_stops_runtime_and_deletes_worktree() throws Exception {
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
}
