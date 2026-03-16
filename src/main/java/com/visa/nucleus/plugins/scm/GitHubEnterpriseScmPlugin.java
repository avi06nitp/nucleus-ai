package com.visa.nucleus.plugins.scm;

import com.visa.nucleus.core.plugin.ScmPlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ScmPlugin implementation that talks to GitHub (github.com or GitHub Enterprise)
 * via the GitHub REST API. Supports both https://api.github.com and GHE base URLs.
 *
 * Configuration (application.yml):
 *   nucleus.scm.baseUrl  – e.g. https://github.trusted.visa.com/api/v3
 *   nucleus.scm.token    – GitHub PAT (preferably injected from env var GITHUB_TOKEN)
 *   nucleus.scm.owner    – org or username
 *   nucleus.scm.repo     – repository name
 */
@Component
public class GitHubEnterpriseScmPlugin implements ScmPlugin {

    private final String baseUrl;
    private final String token;
    private final String owner;
    private final String repo;
    private final RestTemplate restTemplate;

    public GitHubEnterpriseScmPlugin(
            @Value("${nucleus.scm.baseUrl:https://api.github.com}") String baseUrl,
            @Value("${nucleus.scm.token:${GITHUB_TOKEN:}}") String token,
            @Value("${nucleus.scm.owner:owner}") String owner,
            @Value("${nucleus.scm.repo:repo}") String repo,
            RestTemplate restTemplate) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.owner = owner;
        this.repo = repo;
        this.restTemplate = restTemplate;
    }

    // -------------------------------------------------------------------------
    // ScmPlugin implementation
    // -------------------------------------------------------------------------

    /**
     * Creates a pull request and returns its html_url.
     * POST /repos/{owner}/{repo}/pulls
     */
    @Override
    public String createPullRequest(String branch, String title, String body) throws Exception {
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls";

        Map<String, String> payload = Map.of(
                "title", title,
                "head", branch,
                "base", "main",
                "body", body
        );

        HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, authHeaders());

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.POST, request, Map.class);

        Map<?, ?> responseBody = response.getBody();
        if (responseBody == null || !responseBody.containsKey("html_url")) {
            throw new IllegalStateException("GitHub API did not return html_url in PR creation response");
        }
        return (String) responseBody.get("html_url");
    }

    /**
     * Returns the body of each review comment on the PR identified by prUrl.
     * GET /repos/{owner}/{repo}/pulls/{prNumber}/comments
     */
    @Override
    public List<String> getReviewComments(String prUrl) throws Exception {
        int prNumber = extractPrNumber(prUrl);
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/comments";

        HttpEntity<Void> request = new HttpEntity<>(authHeaders());

        @SuppressWarnings("unchecked")
        ResponseEntity<List> response = restTemplate.exchange(url, HttpMethod.GET, request, List.class);

        List<?> comments = response.getBody();
        List<String> bodies = new ArrayList<>();
        if (comments != null) {
            for (Object comment : comments) {
                if (comment instanceof Map<?, ?> map && map.containsKey("body")) {
                    bodies.add((String) map.get("body"));
                }
            }
        }
        return bodies;
    }

    /**
     * Fetches CI logs for the branch associated with the given PR URL.
     * GET /repos/{owner}/{repo}/actions/runs?branch={branch}
     * then GET /repos/{owner}/{repo}/actions/runs/{runId}/logs
     */
    @Override
    public String getCiLogs(String prUrl) throws Exception {
        String branch = fetchBranchForPr(prUrl);

        String runsUrl = baseUrl + "/repos/" + owner + "/" + repo + "/actions/runs?branch=" + branch;
        HttpEntity<Void> request = new HttpEntity<>(authHeaders());

        @SuppressWarnings("unchecked")
        ResponseEntity<Map> runsResponse = restTemplate.exchange(runsUrl, HttpMethod.GET, request, Map.class);

        Map<?, ?> runsBody = runsResponse.getBody();
        if (runsBody == null) {
            return "";
        }

        List<?> runs = (List<?>) runsBody.get("workflow_runs");
        if (runs == null || runs.isEmpty()) {
            return "";
        }

        // Use the most recent run
        Map<?, ?> latestRun = (Map<?, ?>) runs.get(0);
        Object runId = latestRun.get("id");

        String logsUrl = baseUrl + "/repos/" + owner + "/" + repo + "/actions/runs/" + runId + "/logs";

        @SuppressWarnings("unchecked")
        ResponseEntity<String> logsResponse = restTemplate.exchange(logsUrl, HttpMethod.GET, request, String.class);

        String logs = logsResponse.getBody();
        return logs != null ? logs : "";
    }

    /**
     * Replies to an inline PR review comment.
     * POST /repos/{owner}/{repo}/pulls/comments/{commentId}/replies
     */
    @Override
    public void replyToComment(String commentId, String reply) throws Exception {
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls/comments/" + commentId + "/replies";

        Map<String, String> payload = Map.of("body", reply);
        HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, authHeaders());

        restTemplate.exchange(url, HttpMethod.POST, request, Void.class);
    }

    /**
     * Merges the pull request identified by prUrl using a merge commit.
     * PUT /repos/{owner}/{repo}/pulls/{prNumber}/merge
     */
    @Override
    public void mergePullRequest(String prUrl) throws Exception {
        int prNumber = extractPrNumber(prUrl);
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber + "/merge";

        Map<String, String> payload = Map.of("merge_method", "merge");
        HttpEntity<Map<String, String>> request = new HttpEntity<>(payload, authHeaders());

        restTemplate.exchange(url, HttpMethod.PUT, request, Void.class);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private HttpHeaders authHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Accept", "application/vnd.github+json");
        return headers;
    }

    /**
     * Extracts the PR number from a GitHub PR URL such as
     * https://github.trusted.visa.com/org/repo/pull/42
     */
    private int extractPrNumber(String prUrl) {
        Pattern pattern = Pattern.compile(".*/pull/(\\d+)");
        Matcher matcher = pattern.matcher(prUrl);
        if (!matcher.find()) {
            throw new IllegalArgumentException("Cannot extract PR number from URL: " + prUrl);
        }
        return Integer.parseInt(matcher.group(1));
    }

    /**
     * Looks up the head branch of the PR identified by prUrl via the API.
     * GET /repos/{owner}/{repo}/pulls/{prNumber}
     */
    @SuppressWarnings("unchecked")
    private String fetchBranchForPr(String prUrl) throws Exception {
        int prNumber = extractPrNumber(prUrl);
        String url = baseUrl + "/repos/" + owner + "/" + repo + "/pulls/" + prNumber;

        HttpEntity<Void> request = new HttpEntity<>(authHeaders());
        ResponseEntity<Map> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class);

        Map<?, ?> body = response.getBody();
        if (body == null) {
            throw new IllegalStateException("GitHub API returned empty response for PR: " + prUrl);
        }
        Map<?, ?> head = (Map<?, ?>) body.get("head");
        if (head == null || !head.containsKey("ref")) {
            throw new IllegalStateException("Cannot determine branch from PR response: " + prUrl);
        }
        return (String) head.get("ref");
    }
}
