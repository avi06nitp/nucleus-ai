package com.visa.nucleus.plugins.tracker;

import com.visa.nucleus.core.plugin.TrackerPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * TrackerPlugin implementation that integrates with the GitHub Issues REST API.
 *
 * Configuration:
 *   nucleus.tracker.github.owner  - GitHub org or username
 *   nucleus.tracker.github.repo   - repository name
 *   GITHUB_TOKEN env var          - personal access token (already used by SCM plugin)
 *
 * Authentication: Bearer token via Authorization header.
 */
public class GitHubIssuesTrackerPlugin implements TrackerPlugin {

    private final String owner;
    private final String repo;
    private final String token;
    private final HttpClient httpClient;

    static final String API_BASE = "https://api.github.com";

    public GitHubIssuesTrackerPlugin(String owner, String repo, String token) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.httpClient = HttpClient.newHttpClient();
    }

    // For testing — allows injecting a mock HttpClient
    GitHubIssuesTrackerPlugin(String owner, String repo, String token, HttpClient httpClient) {
        this.owner = owner;
        this.repo = repo;
        this.token = token;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the issue and returns a formatted context string:
     * <pre>
     * Issue #123: {title}
     * Description: {body}
     * Labels: {label1, label2}
     * </pre>
     */
    @Override
    public String getIssueContext(String issueNumber) throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + issueNumber;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch GitHub issue " + issueNumber + ": HTTP " + response.statusCode());
        }

        return parseIssueContext(response.body(), issueNumber);
    }

    /**
     * Posts a comment on the specified GitHub issue.
     * POST /repos/{owner}/{repo}/issues/{issueNumber}/comments
     */
    @Override
    public void addComment(String issueNumber, String comment) throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + issueNumber + "/comments";
        String body = "{\"body\":\"" + escapeJson(comment) + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to add comment to GitHub issue " + issueNumber + ": HTTP " + response.statusCode());
        }
    }

    /**
     * Transitions the issue status using GitHub label and state operations:
     * <ul>
     *   <li>"In Progress" → adds label {@code in-progress}</li>
     *   <li>"Done" → closes the issue (PATCH state=closed)</li>
     * </ul>
     */
    @Override
    public void transitionStatus(String issueNumber, String toStatus) throws Exception {
        if (toStatus == null) {
            throw new IllegalArgumentException("toStatus must not be null");
        }
        switch (toStatus.toLowerCase()) {
            case "in progress" -> addLabel(issueNumber, "in-progress");
            case "done" -> closeIssue(issueNumber);
            default -> throw new IllegalArgumentException("Unsupported GitHub status transition: " + toStatus);
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void addLabel(String issueNumber, String label) throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + issueNumber + "/labels";
        String body = "{\"labels\":[\"" + escapeJson(label) + "\"]}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to add label '" + label + "' to issue " + issueNumber + ": HTTP " + response.statusCode());
        }
    }

    private void closeIssue(String issueNumber) throws Exception {
        String url = API_BASE + "/repos/" + owner + "/" + repo + "/issues/" + issueNumber;
        String body = "{\"state\":\"closed\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("Content-Type", "application/json")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to close GitHub issue " + issueNumber + ": HTTP " + response.statusCode());
        }
    }

    /**
     * Parses the GitHub issue JSON response into a formatted context string.
     */
    String parseIssueContext(String json, String issueNumber) {
        StringBuilder sb = new StringBuilder();

        String title = extractJsonStringField(json, "title");
        sb.append("Issue #").append(issueNumber).append(": ").append(title != null ? title : "").append("\n");

        String body = extractJsonStringField(json, "body");
        if (body != null && !body.isBlank()) {
            sb.append("Description: ").append(body).append("\n");
        }

        // Labels array: [{"id":...,"name":"bug",...},...]
        String labels = extractLabelNames(json);
        if (labels != null && !labels.isBlank()) {
            sb.append("Labels: ").append(labels).append("\n");
        }

        return sb.toString().strip();
    }

    /**
     * Extracts all label names from the GitHub issue JSON response.
     * Labels are in a JSON array of objects with a "name" field.
     */
    private String extractLabelNames(String json) {
        int labelsIdx = json.indexOf("\"labels\":[");
        if (labelsIdx == -1) return null;

        int arrayStart = json.indexOf("[", labelsIdx);
        int arrayEnd = findArrayEnd(json, arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return null;

        String labelsArray = json.substring(arrayStart + 1, arrayEnd);
        if (labelsArray.isBlank()) return null;

        // Extract each "name" value from label objects
        StringBuilder names = new StringBuilder();
        int idx = 0;
        while (idx < labelsArray.length()) {
            int nameIdx = labelsArray.indexOf("\"name\":", idx);
            if (nameIdx == -1) break;
            int nameStart = labelsArray.indexOf("\"", nameIdx + 7) + 1;
            int nameEnd = findStringEnd(labelsArray, nameStart);
            if (nameStart < 1 || nameEnd == -1) break;
            String name = unescapeJson(labelsArray.substring(nameStart, nameEnd));
            if (names.length() > 0) names.append(", ");
            names.append(name);
            idx = nameEnd + 1;
        }
        return names.length() > 0 ? names.toString() : null;
    }

    private String extractJsonStringField(String json, String key) {
        String search = "\"" + key + "\":\"";
        int idx = json.indexOf(search);
        if (idx == -1) {
            search = "\"" + key + "\": \"";
            idx = json.indexOf(search);
        }
        if (idx == -1) return null;
        int valueStart = json.indexOf("\"", idx + search.length() - 1) + 1;
        int valueEnd = findStringEnd(json, valueStart);
        if (valueEnd == -1) return null;
        return unescapeJson(json.substring(valueStart, valueEnd));
    }

    private int findStringEnd(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    private int findArrayEnd(String json, int from) {
        int depth = 0;
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private String unescapeJson(String s) {
        return s.replace("\\\"", "\"")
                .replace("\\n", "\n")
                .replace("\\r", "\r")
                .replace("\\t", "\t")
                .replace("\\\\", "\\");
    }
}
