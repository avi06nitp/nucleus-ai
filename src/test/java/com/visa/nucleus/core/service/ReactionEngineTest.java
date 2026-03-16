package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.ScmPlugin;
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
class ReactionEngineTest {

    @Mock private OrchestratorService orchestratorService;
    @Mock private NotifierPlugin notifierPlugin;
    @Mock private ScmPlugin scmPlugin;
    @Mock private SessionManager sessionManager;
    @Mock private NucleusProperties nucleusProperties;

    private ReactionEngine engine;

    private AgentSession session;
    private static final String SESSION_ID = "test-session-id";

    @BeforeEach
    void setUp() {
        engine = new ReactionEngine(orchestratorService, List.of(notifierPlugin), scmPlugin, sessionManager, nucleusProperties);

        session = new AgentSession("proj", "T-1");
        when(sessionManager.getSession(SESSION_ID)).thenReturn(Optional.of(session));
        when(nucleusProperties.getReactions()).thenReturn(new HashMap<>());
    }

    // -------------------------------------------------------------------------
    // onCiFailed — auto: true (default)
    // -------------------------------------------------------------------------

    @Test
    void onCiFailed_autoTrue_forwardsToOrchestrator() throws Exception {
        ReactionRule rule = rule(true, 3);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("ci-failed", rule));

        engine.onCiFailed(SESSION_ID, "compile error");

        verify(orchestratorService).handleCiFailure(SESSION_ID, "compile error");
        verifyNoInteractions(notifierPlugin);
    }

    @Test
    void onCiFailed_autoTrue_noRetryExceeded_doesNotNotify() throws Exception {
        ReactionRule rule = rule(true, 3);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("ci-failed", rule));
        session.incrementCiRetryCount(); // count = 1, limit = 3

        engine.onCiFailed(SESSION_ID, "logs");

        verify(orchestratorService).handleCiFailure(SESSION_ID, "logs");
        verifyNoInteractions(notifierPlugin);
    }

    @Test
    void onCiFailed_autoTrue_exceedsRetryLimit_sendsNeedsAttention() throws Exception {
        ReactionRule rule = rule(true, 2);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("ci-failed", rule));
        // orchestratorService is a mock (won't actually increment ciRetryCount),
        // so pre-set count above the limit so the escalation check fires
        session.incrementCiRetryCount(); // 1
        session.incrementCiRetryCount(); // 2
        session.incrementCiRetryCount(); // 3 > limit(2) → should escalate

        engine.onCiFailed(SESSION_ID, "logs");

        verify(orchestratorService).handleCiFailure(SESSION_ID, "logs");
        verify(notifierPlugin).notify(eq(SESSION_ID), anyString(), eq(NotificationLevel.NEEDS_ATTENTION));
    }

    @Test
    void onCiFailed_autoFalse_notifiesWithoutForwardingToAgent() throws Exception {
        ReactionRule rule = rule(false, 3);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("ci-failed", rule));

        engine.onCiFailed(SESSION_ID, "logs");

        verifyNoInteractions(orchestratorService);
        verify(notifierPlugin).notify(eq(SESSION_ID), anyString(), eq(NotificationLevel.NEEDS_ATTENTION));
    }

    @Test
    void onCiFailed_noRule_defaultBehaviourForwardsToOrchestrator() throws Exception {
        // no reaction rule configured
        engine.onCiFailed(SESSION_ID, "logs");

        verify(orchestratorService).handleCiFailure(SESSION_ID, "logs");
    }

    @Test
    void onCiFailed_throwsForUnknownSession() {
        when(sessionManager.getSession("unknown")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> engine.onCiFailed("unknown", "logs"));
    }

    // -------------------------------------------------------------------------
    // onChangesRequested
    // -------------------------------------------------------------------------

    @Test
    void onChangesRequested_autoTrue_forwardsToOrchestrator() throws Exception {
        ReactionRule rule = rule(true, 0);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        engine.onChangesRequested(SESSION_ID, "Please fix the null check.");

        verify(orchestratorService).handleReviewComment(SESSION_ID, "Please fix the null check.");
        verifyNoInteractions(notifierPlugin);
    }

    @Test
    void onChangesRequested_autoFalse_notifiesWithoutForwarding() throws Exception {
        ReactionRule rule = rule(false, 0);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        engine.onChangesRequested(SESSION_ID, "Please fix the null check.");

        verifyNoInteractions(orchestratorService);
        verify(notifierPlugin).notify(eq(SESSION_ID), anyString(), eq(NotificationLevel.NEEDS_ATTENTION));
    }

    @Test
    void onChangesRequested_noRule_defaultForwardsToOrchestrator() throws Exception {
        engine.onChangesRequested(SESSION_ID, "comment");

        verify(orchestratorService).handleReviewComment(SESSION_ID, "comment");
    }

    // -------------------------------------------------------------------------
    // onApprovedAndGreen
    // -------------------------------------------------------------------------

    @Test
    void onApprovedAndGreen_autoTrue_mergesPullRequest() throws Exception {
        ReactionRule rule = rule(true, 0);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("approved-and-green", rule));

        engine.onApprovedAndGreen(SESSION_ID, "https://github.com/org/repo/pull/42");

        verify(scmPlugin).mergePullRequest("https://github.com/org/repo/pull/42");
        assertEquals(AgentSession.Status.MERGED, session.getStatus());
        verify(sessionManager).save(session);
        verifyNoInteractions(notifierPlugin);
    }

    @Test
    void onApprovedAndGreen_autoFalse_sendsReadyToMergeNotification() throws Exception {
        ReactionRule rule = rule(false, 0);
        when(nucleusProperties.getReactions()).thenReturn(Map.of("approved-and-green", rule));

        engine.onApprovedAndGreen(SESSION_ID, "https://github.com/org/repo/pull/42");

        verifyNoInteractions(scmPlugin);
        verify(notifierPlugin).notify(eq(SESSION_ID), anyString(), eq(NotificationLevel.READY_TO_MERGE));
    }

    @Test
    void onApprovedAndGreen_noRule_sendsReadyToMergeNotification() throws Exception {
        engine.onApprovedAndGreen(SESSION_ID, "https://github.com/org/repo/pull/42");

        verifyNoInteractions(scmPlugin);
        verify(notifierPlugin).notify(eq(SESSION_ID), anyString(), eq(NotificationLevel.READY_TO_MERGE));
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private ReactionRule rule(boolean auto, int retries) {
        ReactionRule r = new ReactionRule();
        r.setAuto(auto);
        r.setRetries(retries);
        return r;
    }
}
