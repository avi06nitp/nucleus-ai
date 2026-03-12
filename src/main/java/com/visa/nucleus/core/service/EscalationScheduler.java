package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scheduled task that checks all RUNNING sessions for escalation timeouts
 * configured via the {@code reactions.changes-requested.escalateAfter} rule
 * in {@code agent-orchestrator.yaml}.
 *
 * <p>Runs every minute. If a session's {@code updatedAt} is older than the
 * configured {@code escalateAfter} duration, a {@code NEEDS_ATTENTION}
 * notification is sent.</p>
 */
@Component
public class EscalationScheduler {

    private static final Logger log = Logger.getLogger(EscalationScheduler.class.getName());
    private static final Pattern DURATION_PATTERN = Pattern.compile("(\\d+)(m|h|d)");

    private final AgentSessionRepository sessionRepository;
    private final NotifierPlugin notifierPlugin;
    private final NucleusProperties nucleusProperties;

    public EscalationScheduler(
            AgentSessionRepository sessionRepository,
            NotifierPlugin notifierPlugin,
            NucleusProperties nucleusProperties) {
        this.sessionRepository = sessionRepository;
        this.notifierPlugin = notifierPlugin;
        this.nucleusProperties = nucleusProperties;
    }

    @Scheduled(fixedDelay = 60_000)
    public void checkEscalations() {
        ReactionRule rule = nucleusProperties.getReactions().get("changes-requested");
        if (rule == null || rule.getEscalateAfter() == null || rule.getEscalateAfter().isBlank()) {
            return;
        }

        long thresholdMinutes = parseMinutes(rule.getEscalateAfter());
        if (thresholdMinutes <= 0) {
            return;
        }

        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(thresholdMinutes);
        List<AgentSession> running = sessionRepository.findAll().stream()
                .filter(s -> AgentSession.Status.RUNNING == s.getStatus())
                .filter(s -> s.getUpdatedAt() != null && s.getUpdatedAt().isBefore(cutoff))
                .toList();

        for (AgentSession session : running) {
            try {
                log.warning("Session " + session.getSessionId()
                        + " has been RUNNING without update for >" + rule.getEscalateAfter()
                        + " — escalating.");
                notifierPlugin.notify(
                        session.getSessionId(),
                        "Session " + session.getSessionId()
                                + " has not responded in " + rule.getEscalateAfter() + ". Needs attention.",
                        NotificationLevel.NEEDS_ATTENTION);
            } catch (Exception e) {
                log.warning("Failed to escalate session " + session.getSessionId() + ": " + e.getMessage());
            }
        }
    }

    /**
     * Parses a duration string like "30m", "2h", or "1d" into minutes.
     * Returns 0 if the string cannot be parsed.
     */
    long parseMinutes(String duration) {
        if (duration == null) return 0;
        Matcher matcher = DURATION_PATTERN.matcher(duration.trim());
        if (!matcher.matches()) return 0;
        long value = Long.parseLong(matcher.group(1));
        return switch (matcher.group(2)) {
            case "m" -> value;
            case "h" -> value * 60;
            case "d" -> value * 60 * 24;
            default  -> 0;
        };
    }
}
