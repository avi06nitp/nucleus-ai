package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.ScmPlugin;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.logging.Logger;

/**
 * ReactionEngine drives all automated responses to external events (CI failures,
 * review comments, PR approvals) based on rules configured in
 * {@code agent-orchestrator.yaml} under the {@code reactions} key.
 *
 * <p>WebhookController calls this service instead of OrchestratorService directly,
 * so that reaction behavior is fully config-driven and not hardcoded.</p>
 *
 * <h3>Supported reactions</h3>
 * <ul>
 *   <li>{@code ci-failed} – forward CI logs to agent or notify immediately</li>
 *   <li>{@code changes-requested} – forward reviewer comment to agent or notify</li>
 *   <li>{@code approved-and-green} – auto-merge or send READY_TO_MERGE notification</li>
 * </ul>
 */
@Service
public class ReactionEngine {

    private static final Logger log = Logger.getLogger(ReactionEngine.class.getName());

    static final String REACTION_CI_FAILED         = "ci-failed";
    static final String REACTION_CHANGES_REQUESTED = "changes-requested";
    static final String REACTION_APPROVED_GREEN     = "approved-and-green";

    private static final int DEFAULT_CI_RETRIES = 3;

    private final OrchestratorService orchestratorService;
    private final List<NotifierPlugin> notifierPlugins;
    private final ScmPlugin scmPlugin;
    private final SessionManager sessionManager;
    private final NucleusProperties nucleusProperties;

    public ReactionEngine(
            OrchestratorService orchestratorService,
            List<NotifierPlugin> notifierPlugins,
            ScmPlugin scmPlugin,
            SessionManager sessionManager,
            NucleusProperties nucleusProperties) {
        this.orchestratorService = orchestratorService;
        this.notifierPlugins = notifierPlugins;
        this.scmPlugin = scmPlugin;
        this.sessionManager = sessionManager;
        this.nucleusProperties = nucleusProperties;
    }

    // -------------------------------------------------------------------------
    // Public entry points called by WebhookController
    // -------------------------------------------------------------------------

    /**
     * Handles a CI failure event.
     *
     * <ul>
     *   <li>If {@code auto: false} in the rule → notify immediately without forwarding to agent.</li>
     *   <li>If {@code auto: true} and retry count ≤ configured limit → forward logs to agent.</li>
     *   <li>If retry count exceeds limit → notify with {@code NEEDS_ATTENTION}.</li>
     * </ul>
     */
    public void onCiFailed(String sessionId, String ciLogs) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        ReactionRule rule = nucleusProperties.getReactions().get(REACTION_CI_FAILED);

        if (rule != null && !rule.isAuto()) {
            log.info("ci-failed reaction auto=false; notifying without forwarding to agent. session=" + sessionId);
            notifyAll(sessionId, "CI failed for session " + sessionId + ". Manual review required.",
                    NotificationLevel.NEEDS_ATTENTION);
            return;
        }

        // Forward to orchestrator (increments ciRetryCount)
        orchestratorService.handleCiFailure(sessionId, ciLogs);

        // Re-read retry count after increment
        int retryLimit = (rule != null && rule.getRetries() > 0) ? rule.getRetries() : DEFAULT_CI_RETRIES;
        session = sessionManager.getSession(sessionId).orElseThrow();

        if (session.getCiRetryCount() > retryLimit) {
            log.warning("CI retry limit exceeded for session=" + sessionId
                    + " (count=" + session.getCiRetryCount() + ", limit=" + retryLimit + ")");
            notifyAll(sessionId, "Session " + sessionId + " has exceeded the CI retry limit ("
                    + retryLimit + " retries). Needs attention.", NotificationLevel.NEEDS_ATTENTION);
        }
    }

    /**
     * Handles a review comment (changes-requested) event.
     *
     * <ul>
     *   <li>If {@code auto: false} → notify without forwarding to agent.</li>
     *   <li>If {@code auto: true} → forward comment to agent via OrchestratorService.</li>
     * </ul>
     */
    public void onChangesRequested(String sessionId, String comment) throws Exception {
        ReactionRule rule = nucleusProperties.getReactions().get(REACTION_CHANGES_REQUESTED);

        if (rule != null && !rule.isAuto()) {
            log.info("changes-requested reaction auto=false; notifying without forwarding. session=" + sessionId);
            notifyAll(sessionId, "Review comment received for session " + sessionId + ": " + comment,
                    NotificationLevel.NEEDS_ATTENTION);
            return;
        }

        orchestratorService.handleReviewComment(sessionId, comment);
    }

    /**
     * Handles a PR-approved-and-CI-green event.
     *
     * <ul>
     *   <li>If {@code auto: true} → attempt to merge via ScmPlugin.</li>
     *   <li>If {@code auto: false} (or no rule) → send {@code READY_TO_MERGE} notification.</li>
     * </ul>
     */
    public void onApprovedAndGreen(String sessionId, String prUrl) throws Exception {
        ReactionRule rule = nucleusProperties.getReactions().get(REACTION_APPROVED_GREEN);

        if (rule != null && rule.isAuto()) {
            log.info("approved-and-green auto=true; merging PR " + prUrl + " for session=" + sessionId);
            scmPlugin.mergePullRequest(prUrl);
            AgentSession session = sessionManager.getSession(sessionId).orElseThrow();
            session.setStatus(AgentSession.Status.MERGED);
            sessionManager.save(session);
        } else {
            log.info("approved-and-green auto=false; sending READY_TO_MERGE notification for session=" + sessionId);
            notifyAll(sessionId, "PR is approved and CI is green for session " + sessionId
                    + ". Ready to merge: " + prUrl, NotificationLevel.READY_TO_MERGE);
        }
    }

    private void notifyAll(String sessionId, String message, NotificationLevel level) throws Exception {
        for (NotifierPlugin notifier : notifierPlugins) {
            notifier.notify(sessionId, message, level);
        }
    }
}
