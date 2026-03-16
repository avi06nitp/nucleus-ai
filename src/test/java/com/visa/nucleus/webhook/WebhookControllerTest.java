package com.visa.nucleus.webhook;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import com.visa.nucleus.core.ReactionEventRepository;
import com.visa.nucleus.core.service.ReactionEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookControllerTest {

    private static final String SECRET = "test-webhook-secret";
    private static final String BRANCH = "feat/issue-42";

    @Mock
    private ReactionEngine reactionEngine;

    @Mock
    private AgentSessionRepository sessionRepository;

    @Mock
    private ReactionEventRepository reactionEventRepository;

    private WebhookController controller;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        controller = new WebhookController(reactionEngine, sessionRepository, reactionEventRepository, objectMapper, SECRET);
    }

    // -------------------------------------------------------------------------
    // Signature verification
    // -------------------------------------------------------------------------

    @Test
    void verifySignature_acceptsValidSignature() throws Exception {
        byte[] body = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
        String sig = computeSignature(SECRET, body);
        assertTrue(controller.verifySignature(body, sig));
    }

    @Test
    void verifySignature_rejectsTamperedBody() throws Exception {
        byte[] originalBody = "{\"test\":true}".getBytes(StandardCharsets.UTF_8);
        byte[] tamperedBody = "{\"test\":false}".getBytes(StandardCharsets.UTF_8);
        String sig = computeSignature(SECRET, originalBody);
        assertFalse(controller.verifySignature(tamperedBody, sig));
    }

    @Test
    void verifySignature_rejectsBlankSignature() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertFalse(controller.verifySignature(body, ""));
    }

    @Test
    void verifySignature_skipsCheckWhenSecretNotConfigured() {
        WebhookController noSecretController =
                new WebhookController(reactionEngine, sessionRepository, reactionEventRepository, objectMapper, "");
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        assertTrue(noSecretController.verifySignature(body, ""));
    }

    // -------------------------------------------------------------------------
    // GitHub – check_run (CI failure)
    // -------------------------------------------------------------------------

    @Test
    void handleGitHubEvent_checkRunFailure_callsOrchestratorHandleCiFailure() throws Exception {
        AgentSession session = new AgentSession("proj", "ISSUE-42");
        session.setBranchName(BRANCH);
        when(sessionRepository.findByBranchName(BRANCH)).thenReturn(Optional.of(session));

        String json = """
                {
                  "check_run": {
                    "conclusion": "failure",
                    "check_suite": { "head_branch": "%s" },
                    "output": { "text": "Build failed at step compile" }
                  }
                }
                """.formatted(BRANCH);

        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response = controller.handleGitHubEvent(
                "check_run", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reactionEngine).onCiFailed(eq(session.getSessionId()), eq("Build failed at step compile"));
    }

    @Test
    void handleGitHubEvent_checkRunSuccess_doesNotCallOrchestrator() throws Exception {
        String json = """
                { "check_run": { "conclusion": "success", "check_suite": { "head_branch": "%s" } } }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "check_run", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(reactionEngine);
    }

    @Test
    void handleGitHubEvent_checkRunFailure_noSession_doesNotCallReactionEngine() throws Exception {
        when(sessionRepository.findByBranchName(BRANCH)).thenReturn(Optional.empty());

        String json = """
                {
                  "check_run": {
                    "conclusion": "failure",
                    "check_suite": { "head_branch": "%s" },
                    "output": { "text": "Error" }
                  }
                }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "check_run", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(reactionEngine);
    }

    // -------------------------------------------------------------------------
    // GitHub – pull_request_review_comment
    // -------------------------------------------------------------------------

    @Test
    void handleGitHubEvent_reviewComment_callsOrchestratorHandleReviewComment() throws Exception {
        AgentSession session = new AgentSession("proj", "ISSUE-42");
        session.setBranchName(BRANCH);
        when(sessionRepository.findByBranchName(BRANCH)).thenReturn(Optional.of(session));

        String json = """
                {
                  "pull_request": { "head": { "ref": "%s" } },
                  "comment": { "body": "Please rename this variable." }
                }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "pull_request_review_comment", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reactionEngine).onChangesRequested(eq(session.getSessionId()), eq("Please rename this variable."));
    }

    // -------------------------------------------------------------------------
    // GitHub – pull_request_review (approved-and-green)
    // -------------------------------------------------------------------------

    @Test
    void handleGitHubEvent_pullRequestReviewApproved_callsOnApprovedAndGreen() throws Exception {
        AgentSession session = new AgentSession("proj", "ISSUE-42");
        session.setBranchName(BRANCH);
        when(sessionRepository.findByBranchName(BRANCH)).thenReturn(Optional.of(session));

        String prUrl = "https://github.example.com/visa-org/nucleus/pull/42";
        String json = """
                {
                  "review": { "state": "approved" },
                  "pull_request": {
                    "head": { "ref": "%s" },
                    "html_url": "%s"
                  }
                }
                """.formatted(BRANCH, prUrl);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "pull_request_review", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verify(reactionEngine).onApprovedAndGreen(eq(session.getSessionId()), eq(prUrl));
        verify(reactionEventRepository).save(any());
    }

    @Test
    void handleGitHubEvent_pullRequestReviewChangesRequested_doesNotCallApprovedAndGreen() throws Exception {
        String json = """
                {
                  "review": { "state": "changes_requested" },
                  "pull_request": { "head": { "ref": "%s" }, "html_url": "https://example.com/pull/1" }
                }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "pull_request_review", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(reactionEngine);
    }

    // -------------------------------------------------------------------------
    // GitHub – pull_request merged
    // -------------------------------------------------------------------------

    @Test
    void handleGitHubEvent_pullRequestMerged_setsSessionStatusMerged() throws Exception {
        AgentSession session = new AgentSession("proj", "ISSUE-42");
        session.setBranchName(BRANCH);
        when(sessionRepository.findByBranchName(BRANCH)).thenReturn(Optional.of(session));

        String json = """
                {
                  "action": "closed",
                  "pull_request": { "merged": true, "head": { "ref": "%s" } }
                }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "pull_request", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(AgentSession.Status.MERGED, session.getStatus());
        verify(sessionRepository).save(session);
        verifyNoInteractions(reactionEngine);
    }

    @Test
    void handleGitHubEvent_pullRequestClosedNotMerged_doesNotUpdateSession() throws Exception {
        String json = """
                {
                  "action": "closed",
                  "pull_request": { "merged": false, "head": { "ref": "%s" } }
                }
                """.formatted(BRANCH);
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleGitHubEvent(
                "pull_request", computeSignature(SECRET, body), body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        verifyNoInteractions(sessionRepository, reactionEngine);
    }

    // -------------------------------------------------------------------------
    // Signature rejection
    // -------------------------------------------------------------------------

    @Test
    void handleGitHubEvent_invalidSignature_returnsUnauthorized() {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response =
                controller.handleGitHubEvent("check_run", "sha256=badhash", body);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
        verifyNoInteractions(reactionEngine, sessionRepository);
    }

    // -------------------------------------------------------------------------
    // Jira webhook
    // -------------------------------------------------------------------------

    @Test
    void handleJiraEvent_transitionEvent_returnsOk() throws Exception {
        String json = """
                {
                  "webhookEvent": "jira:issue_updated",
                  "issue": { "key": "PROJ-123" },
                  "transition": { "from_status": "In Progress", "to_status": "Done" }
                }
                """;
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleJiraEvent(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("ok", response.getBody());
    }

    @Test
    void handleJiraEvent_noTransition_returnsOk() throws Exception {
        String json = """
                { "webhookEvent": "jira:issue_created", "issue": { "key": "PROJ-456" } }
                """;
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        ResponseEntity<String> response = controller.handleJiraEvent(body);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    void handleJiraEvent_malformedJson_returnsInternalServerError() {
        byte[] body = "not-json".getBytes(StandardCharsets.UTF_8);
        ResponseEntity<String> response = controller.handleJiraEvent(body);
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private static String computeSignature(String secret, byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
    }
}
