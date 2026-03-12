package com.visa.nucleus.plugins.scm;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitHubEnterpriseScmPluginTest {

    private static final String BASE_URL = "https://github.example.com/api/v3";
    private static final String TOKEN    = "test-token";
    private static final String OWNER    = "visa-org";
    private static final String REPO     = "nucleus";
    private static final String PR_URL   = "https://github.example.com/visa-org/nucleus/pull/42";

    @Mock
    private RestTemplate restTemplate;

    private GitHubEnterpriseScmPlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitHubEnterpriseScmPlugin(BASE_URL, TOKEN, OWNER, REPO, restTemplate);
    }

    // ------------------------------------------------------------------
    // createPullRequest
    // ------------------------------------------------------------------

    @Test
    void createPullRequest_returnsPrUrl() throws Exception {
        Map<String, Object> apiResponse = Map.of("html_url", PR_URL, "number", 42);
        when(restTemplate.exchange(
                contains("/pulls"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(apiResponse, HttpStatus.CREATED));

        String result = plugin.createPullRequest("feature-branch", "My PR", "body text");

        assertEquals(PR_URL, result);
    }

    @Test
    void createPullRequest_throwsWhenNoHtmlUrl() {
        Map<String, Object> apiResponse = Map.of("number", 42);
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(HttpEntity.class), eq(Map.class)))
                .thenReturn(new ResponseEntity<>(apiResponse, HttpStatus.CREATED));

        assertThrows(IllegalStateException.class,
                () -> plugin.createPullRequest("feature-branch", "title", "body"));
    }

    // ------------------------------------------------------------------
    // getReviewComments
    // ------------------------------------------------------------------

    @Test
    void getReviewComments_returnsCommentBodies() throws Exception {
        List<Map<String, Object>> comments = List.of(
                Map.of("body", "First comment"),
                Map.of("body", "Second comment")
        );
        when(restTemplate.exchange(
                contains("/pulls/42/comments"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(List.class)))
                .thenReturn(new ResponseEntity<>(comments, HttpStatus.OK));

        List<String> result = plugin.getReviewComments(PR_URL);

        assertEquals(List.of("First comment", "Second comment"), result);
    }

    @Test
    void getReviewComments_returnsEmptyListWhenNullBody() throws Exception {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(HttpEntity.class), eq(List.class)))
                .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

        List<String> result = plugin.getReviewComments(PR_URL);

        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // getCiLogs
    // ------------------------------------------------------------------

    @Test
    void getCiLogs_returnsLogsForLatestRun() throws Exception {
        // First call: fetch PR to get branch name
        Map<String, Object> prHead = Map.of("ref", "feature-branch");
        Map<String, Object> prResponse = Map.of("head", prHead);
        when(restTemplate.exchange(
                contains("/pulls/42"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(prResponse, HttpStatus.OK));

        // Second call: list workflow runs
        List<Map<String, Object>> runs = List.of(Map.of("id", 999));
        Map<String, Object> runsBody = Map.of("workflow_runs", runs);
        when(restTemplate.exchange(
                contains("actions/runs?branch=feature-branch"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(runsBody, HttpStatus.OK));

        // Third call: get logs for run 999
        when(restTemplate.exchange(
                contains("actions/runs/999/logs"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(String.class)))
                .thenReturn(new ResponseEntity<>("build logs here", HttpStatus.OK));

        String logs = plugin.getCiLogs(PR_URL);

        assertEquals("build logs here", logs);
    }

    @Test
    void getCiLogs_returnsEmptyStringWhenNoRuns() throws Exception {
        Map<String, Object> prHead = Map.of("ref", "feature-branch");
        Map<String, Object> prResponse = Map.of("head", prHead);
        when(restTemplate.exchange(
                contains("/pulls/42"),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(prResponse, HttpStatus.OK));

        Map<String, Object> runsBody = Map.of("workflow_runs", List.of());
        when(restTemplate.exchange(
                contains("actions/runs?branch="),
                eq(HttpMethod.GET),
                any(HttpEntity.class),
                eq(Map.class)))
                .thenReturn(new ResponseEntity<>(runsBody, HttpStatus.OK));

        String logs = plugin.getCiLogs(PR_URL);

        assertEquals("", logs);
    }

    // ------------------------------------------------------------------
    // replyToComment
    // ------------------------------------------------------------------

    @Test
    void replyToComment_callsCorrectEndpoint() throws Exception {
        when(restTemplate.exchange(
                contains("/pulls/comments/77/replies"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.CREATED));

        assertDoesNotThrow(() -> plugin.replyToComment("77", "Thanks for the feedback!"));

        verify(restTemplate).exchange(
                contains("/pulls/comments/77/replies"),
                eq(HttpMethod.POST),
                any(HttpEntity.class),
                eq(Void.class));
    }

    // ------------------------------------------------------------------
    // mergePullRequest
    // ------------------------------------------------------------------

    @Test
    void mergePullRequest_callsMergeEndpointWithPut() throws Exception {
        when(restTemplate.exchange(
                contains("/pulls/42/merge"),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(Void.class)))
                .thenReturn(new ResponseEntity<>(HttpStatus.OK));

        assertDoesNotThrow(() -> plugin.mergePullRequest(PR_URL));

        verify(restTemplate).exchange(
                contains("/pulls/42/merge"),
                eq(HttpMethod.PUT),
                any(HttpEntity.class),
                eq(Void.class));
    }

    @Test
    void mergePullRequest_throwsForInvalidPrUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> plugin.mergePullRequest("https://github.example.com/visa-org/nucleus/issues/42"));
    }

    // ------------------------------------------------------------------
    // helper – URL parsing
    // ------------------------------------------------------------------

    @Test
    void createPullRequest_throwsForInvalidPrUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> plugin.getReviewComments("https://github.example.com/visa-org/nucleus/issues/42"));
    }
}
