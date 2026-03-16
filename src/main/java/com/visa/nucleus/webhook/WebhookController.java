package com.visa.nucleus.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.ReactionEvent;
import com.visa.nucleus.core.ReactionEventRepository;
import com.visa.nucleus.core.service.ReactionEngine;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Receives and dispatches incoming GitHub and Jira webhook events.
 *
 * <p>GitHub events are authenticated via the {@code X-Hub-Signature-256} header
 * (HMAC-SHA256 of the raw request body, keyed by {@code GITHUB_WEBHOOK_SECRET}).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code POST /api/webhooks/github} – GitHub Actions CI and PR review events</li>
 *   <li>{@code POST /api/webhooks/jira}   – Jira issue-transition events</li>
 * </ul>
 */
@Controller
@RequestMapping("/api/webhooks")
public class WebhookController {

    private static final Logger log = Logger.getLogger(WebhookController.class.getName());
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final ReactionEngine reactionEngine;
    private final AgentSessionRepository sessionRepository;
    private final ReactionEventRepository reactionEventRepository;
    private final ObjectMapper objectMapper;
    private final String webhookSecret;

    public WebhookController(
            ReactionEngine reactionEngine,
            AgentSessionRepository sessionRepository,
            ReactionEventRepository reactionEventRepository,
            ObjectMapper objectMapper,
            @Value("${GITHUB_WEBHOOK_SECRET:}") String webhookSecret) {
        this.reactionEngine = reactionEngine;
        this.sessionRepository = sessionRepository;
        this.reactionEventRepository = reactionEventRepository;
        this.objectMapper = objectMapper;
        this.webhookSecret = webhookSecret;
    }

    @PostConstruct
    void validateConfig() {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalStateException(
                "GITHUB_WEBHOOK_SECRET environment variable is not set. " +
                "Webhook signature verification is disabled — refusing to start.");
        }
    }

    // -------------------------------------------------------------------------
    // GitHub webhook
    // -------------------------------------------------------------------------

    /**
     * Receives GitHub webhook events.
     *
     * <p>Handled event types (via {@code X-GitHub-Event} header):
     * <ul>
     *   <li>{@code check_run} with {@code conclusion == "failure"} → CI failure</li>
     *   <li>{@code pull_request_review_comment} → PR review comment</li>
     *   <li>{@code pull_request} with {@code action == "closed"} and {@code merged == true}
     *       → marks session as MERGED</li>
     * </ul>
     */
    @PostMapping("/github")
    @ResponseBody
    public ResponseEntity<String> handleGitHubEvent(
            @RequestHeader(value = "X-GitHub-Event", defaultValue = "") String eventType,
            @RequestHeader(value = "X-Hub-Signature-256", defaultValue = "") String signature,
            @RequestBody byte[] rawBody) {

        if (!verifySignature(rawBody, signature)) {
            log.warning("GitHub webhook rejected: invalid X-Hub-Signature-256");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid signature");
        }

        try {
            JsonNode payload = objectMapper.readTree(rawBody);

            switch (eventType) {
                case "check_run" -> handleCheckRun(payload);
                case "pull_request_review_comment" -> handleReviewComment(payload);
                case "pull_request_review" -> handlePullRequestReview(payload);
                case "pull_request" -> handlePullRequest(payload);
                default -> log.fine("Ignoring unhandled GitHub event type: " + eventType);
            }

            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error processing GitHub webhook event '" + eventType + "'", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing error");
        }
    }

    // -------------------------------------------------------------------------
    // Jira webhook
    // -------------------------------------------------------------------------

    /**
     * Receives Jira webhook events and logs issue transitions.
     */
    @PostMapping("/jira")
    @ResponseBody
    public ResponseEntity<String> handleJiraEvent(@RequestBody byte[] rawBody) {
        try {
            JsonNode payload = objectMapper.readTree(rawBody);

            String webhookEvent = payload.path("webhookEvent").asText("unknown");
            JsonNode issue = payload.path("issue");
            String issueKey = issue.path("key").asText("unknown");

            JsonNode transition = payload.path("transition");
            if (!transition.isMissingNode()) {
                String fromStatus = transition.path("from_status").asText("unknown");
                String toStatus = transition.path("to_status").asText("unknown");
                log.info("Jira issue " + issueKey + " transitioned: " + fromStatus
                        + " -> " + toStatus + " (event=" + webhookEvent + ")");
            } else {
                log.info("Jira webhook received: event=" + webhookEvent + ", issue=" + issueKey);
            }

            return ResponseEntity.ok("ok");
        } catch (Exception e) {
            log.log(Level.SEVERE, "Error processing Jira webhook", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Processing error");
        }
    }

    // -------------------------------------------------------------------------
    // Private event handlers
    // -------------------------------------------------------------------------

    private void handleCheckRun(JsonNode payload) throws Exception {
        String conclusion = payload.path("check_run").path("conclusion").asText("");
        if (!"failure".equals(conclusion)) {
            return;
        }

        String branchName = payload.path("check_run")
                .path("check_suite")
                .path("head_branch")
                .asText("");

        Optional<AgentSession> sessionOpt = sessionRepository.findByBranchName(branchName);
        if (sessionOpt.isEmpty()) {
            log.fine("No session found for branch '" + branchName + "' on CI failure event");
            return;
        }

        String ciLogs = payload.path("check_run").path("output").path("text").asText("");
        log.info("CI failure detected for branch '" + branchName + "', dispatching to ReactionEngine");
        String sessionId = sessionOpt.get().getSessionId();
        reactionEngine.onCiFailed(sessionId, ciLogs);

        ReactionEvent event = new ReactionEvent();
        event.setSessionId(sessionId);
        event.setEventType("CI_FAILURE");
        event.setPayload(ciLogs);
        event.setCreatedAt(LocalDateTime.now());
        reactionEventRepository.save(event);
    }

    private void handleReviewComment(JsonNode payload) throws Exception {
        String branchName = payload.path("pull_request")
                .path("head")
                .path("ref")
                .asText("");

        Optional<AgentSession> sessionOpt = sessionRepository.findByBranchName(branchName);
        if (sessionOpt.isEmpty()) {
            log.fine("No session found for branch '" + branchName + "' on review comment event");
            return;
        }

        String commentBody = payload.path("comment").path("body").asText("");
        log.info("Review comment received for branch '" + branchName + "', dispatching to ReactionEngine");
        String sessionId = sessionOpt.get().getSessionId();
        reactionEngine.onChangesRequested(sessionId, commentBody);

        ReactionEvent event = new ReactionEvent();
        event.setSessionId(sessionId);
        event.setEventType("REVIEW_COMMENT");
        event.setPayload(commentBody);
        event.setCreatedAt(LocalDateTime.now());
        reactionEventRepository.save(event);
    }

    private void handlePullRequestReview(JsonNode payload) throws Exception {
        String state = payload.path("review").path("state").asText("");
        if (!"approved".equalsIgnoreCase(state)) {
            return;
        }

        String branchName = payload.path("pull_request").path("head").path("ref").asText("");
        String prUrl = payload.path("pull_request").path("html_url").asText("");

        Optional<AgentSession> sessionOpt = sessionRepository.findByBranchName(branchName);
        if (sessionOpt.isEmpty()) {
            log.fine("No session found for branch '" + branchName + "' on PR review approved event");
            return;
        }

        String sessionId = sessionOpt.get().getSessionId();
        log.info("PR approved for branch '" + branchName + "', dispatching approved-and-green to ReactionEngine");
        reactionEngine.onApprovedAndGreen(sessionId, prUrl);

        ReactionEvent event = new ReactionEvent();
        event.setSessionId(sessionId);
        event.setEventType("APPROVED_AND_GREEN");
        event.setPayload(prUrl);
        event.setCreatedAt(LocalDateTime.now());
        reactionEventRepository.save(event);
    }

    private void handlePullRequest(JsonNode payload) {
        String action = payload.path("action").asText("");
        boolean merged = payload.path("pull_request").path("merged").asBoolean(false);

        if (!"closed".equals(action) || !merged) {
            return;
        }

        String branchName = payload.path("pull_request")
                .path("head")
                .path("ref")
                .asText("");

        Optional<AgentSession> sessionOpt = sessionRepository.findByBranchName(branchName);
        if (sessionOpt.isEmpty()) {
            log.fine("No session found for branch '" + branchName + "' on PR merged event");
            return;
        }

        AgentSession session = sessionOpt.get();
        session.setStatus(AgentSession.Status.MERGED);
        sessionRepository.save(session);
        log.info("Session " + session.getSessionId() + " marked as MERGED (branch='" + branchName + "')");
    }

    // -------------------------------------------------------------------------
    // Signature verification
    // -------------------------------------------------------------------------

    /**
     * Verifies the {@code X-Hub-Signature-256} header using HMAC-SHA256.
     */
    boolean verifySignature(byte[] body, String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }

        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] expectedBytes = mac.doFinal(body);
            String expected = "sha256=" + HexFormat.of().formatHex(expectedBytes);
            return constantTimeEquals(expected, signature);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            log.log(Level.SEVERE, "Signature verification failed", e);
            return false;
        }
    }

    /** Constant-time string comparison to prevent timing attacks. */
    private boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
