package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EscalationSchedulerTest {

    @Mock private AgentSessionRepository sessionRepository;
    @Mock private NotifierPlugin notifierPlugin;
    @Mock private NucleusProperties nucleusProperties;

    private EscalationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new EscalationScheduler(sessionRepository, notifierPlugin, nucleusProperties);
        when(nucleusProperties.getReactions()).thenReturn(new HashMap<>());
    }

    // -------------------------------------------------------------------------
    // parseMinutes
    // -------------------------------------------------------------------------

    @Test
    void parseMinutes_parsesMinutes() {
        assertEquals(30, scheduler.parseMinutes("30m"));
    }

    @Test
    void parseMinutes_parsesHours() {
        assertEquals(120, scheduler.parseMinutes("2h"));
    }

    @Test
    void parseMinutes_parsesDays() {
        assertEquals(1440, scheduler.parseMinutes("1d"));
    }

    @Test
    void parseMinutes_returnsZeroForInvalidInput() {
        assertEquals(0, scheduler.parseMinutes("invalid"));
        assertEquals(0, scheduler.parseMinutes(null));
        assertEquals(0, scheduler.parseMinutes(""));
    }

    // -------------------------------------------------------------------------
    // checkEscalations
    // -------------------------------------------------------------------------

    @Test
    void checkEscalations_noRule_doesNothing() throws Exception {
        scheduler.checkEscalations();
        verifyNoInteractions(sessionRepository, notifierPlugin);
    }

    @Test
    void checkEscalations_noEscalateAfter_doesNothing() throws Exception {
        ReactionRule rule = new ReactionRule();
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        scheduler.checkEscalations();

        verifyNoInteractions(sessionRepository, notifierPlugin);
    }

    @Test
    void checkEscalations_sessionExceedsThreshold_notifiesNeedsAttention() throws Exception {
        ReactionRule rule = new ReactionRule();
        rule.setEscalateAfter("30m");
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        AgentSession stale = new AgentSession("proj", "T-1");
        stale.setStatus(AgentSession.Status.RUNNING);
        stale.setUpdatedAt(LocalDateTime.now().minusMinutes(45));
        when(sessionRepository.findAll()).thenReturn(List.of(stale));

        scheduler.checkEscalations();

        verify(notifierPlugin).notify(eq(stale.getSessionId()), anyString(), eq(NotificationLevel.NEEDS_ATTENTION));
    }

    @Test
    void checkEscalations_sessionWithinThreshold_doesNotNotify() throws Exception {
        ReactionRule rule = new ReactionRule();
        rule.setEscalateAfter("30m");
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        AgentSession recent = new AgentSession("proj", "T-2");
        recent.setStatus(AgentSession.Status.RUNNING);
        recent.setUpdatedAt(LocalDateTime.now().minusMinutes(10));
        when(sessionRepository.findAll()).thenReturn(List.of(recent));

        scheduler.checkEscalations();

        verifyNoInteractions(notifierPlugin);
    }

    @Test
    void checkEscalations_onlyEscalatesRunningSessions() throws Exception {
        ReactionRule rule = new ReactionRule();
        rule.setEscalateAfter("5m");
        when(nucleusProperties.getReactions()).thenReturn(Map.of("changes-requested", rule));

        AgentSession failedSession = new AgentSession("proj", "T-3");
        failedSession.setStatus(AgentSession.Status.FAILED);
        failedSession.setUpdatedAt(LocalDateTime.now().minusMinutes(60));

        AgentSession mergedSession = new AgentSession("proj", "T-4");
        mergedSession.setStatus(AgentSession.Status.MERGED);
        mergedSession.setUpdatedAt(LocalDateTime.now().minusMinutes(60));

        when(sessionRepository.findAll()).thenReturn(List.of(failedSession, mergedSession));

        scheduler.checkEscalations();

        verifyNoInteractions(notifierPlugin);
    }
}
