package com.visa.nucleus.plugins.tracker;

import com.visa.nucleus.core.plugin.TrackerPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * TrackerPlugin implementation that integrates with the Linear GraphQL API.
 *
 * Configuration:
 *   LINEAR_API_KEY env var - Linear personal API key
 *
 * Authentication: Bearer token via Authorization header.
 * Endpoint: https://api.linear.app/graphql
 */
public class LinearTrackerPlugin implements TrackerPlugin {

    private final String apiKey;
    private final HttpClient httpClient;

    static final String GRAPHQL_ENDPOINT = "https://api.linear.app/graphql";

    public LinearTrackerPlugin(String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newHttpClient();
    }

    // For testing — allows injecting a mock HttpClient
    LinearTrackerPlugin(String apiKey, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.httpClient = httpClient;
    }

    /**
     * Fetches the Linear issue and returns a formatted context string:
     * <pre>
     * Issue {id}: {title}
     * Description: {description}
     * State: {state}
     * Labels: {label1, label2}
     * </pre>
     */
    @Override
    public String getIssueContext(String issueId) throws Exception {
        String query = "{ \"query\": \"{ issue(id: \\\"" + escapeJson(issueId) + "\\\") { title description state { name } labels { nodes { name } } } }\" }";

        HttpResponse<String> response = sendGraphQL(query);
        return parseIssueContext(response.body(), issueId);
    }

    /**
     * Posts a comment on the specified Linear issue.
     * Uses mutation: commentCreate(input: { issueId: "...", body: "..." })
     */
    @Override
    public void addComment(String issueId, String comment) throws Exception {
        String mutation = "{ \"query\": \"mutation { commentCreate(input: { issueId: \\\"" + escapeJson(issueId) + "\\\", body: \\\"" + escapeJson(comment) + "\\\" }) { success } }\" }";

        HttpResponse<String> response = sendGraphQL(mutation);

        if (!response.body().contains("\"success\":true")) {
            throw new RuntimeException("Failed to add comment to Linear issue " + issueId + ": " + response.body());
        }
    }

    /**
     * Transitions the Linear issue to the given status name.
     * Fetches available workflow states, finds the matching one by name (case-insensitive),
     * then updates the issue via issueUpdate mutation.
     */
    @Override
    public void transitionStatus(String issueId, String toStatus) throws Exception {
        String stateId = findStateId(issueId, toStatus);

        String mutation = "{ \"query\": \"mutation { issueUpdate(id: \\\"" + escapeJson(issueId) + "\\\", input: { stateId: \\\"" + escapeJson(stateId) + "\\\" }) { success } }\" }";

        HttpResponse<String> response = sendGraphQL(mutation);

        if (!response.body().contains("\"success\":true")) {
            throw new RuntimeException("Failed to transition Linear issue " + issueId + " to '" + toStatus + "': " + response.body());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Fetches all workflow states and returns the ID of the state matching toStatus (case-insensitive).
     */
    private String findStateId(String issueId, String toStatus) throws Exception {
        // Fetch team states via the issue's team
        String query = "{ \"query\": \"{ issue(id: \\\"" + escapeJson(issueId) + "\\\") { team { states { nodes { id name } } } } }\" }";

        HttpResponse<String> response = sendGraphQL(query);
        String json = response.body();

        return extractStateId(json, toStatus, issueId);
    }

    private HttpResponse<String> sendGraphQL(String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(GRAPHQL_ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Linear GraphQL API error: HTTP " + response.statusCode() + " — " + response.body());
        }
        return response;
    }

    /**
     * Parses the GraphQL issue response into a formatted context string.
     */
    String parseIssueContext(String json, String issueId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Issue ").append(issueId).append(": ");

        String title = extractJsonStringField(json, "title");
        sb.append(title != null ? title : "").append("\n");

        String description = extractJsonStringField(json, "description");
        if (description != null && !description.isBlank()) {
            sb.append("Description: ").append(description).append("\n");
        }

        String stateName = extractJsonStringField(json, "name");
        if (stateName != null && !stateName.isBlank()) {
            sb.append("State: ").append(stateName).append("\n");
        }

        String labels = extractLabelNames(json);
        if (labels != null && !labels.isBlank()) {
            sb.append("Labels: ").append(labels).append("\n");
        }

        return sb.toString().strip();
    }

    /**
     * Finds a state ID in the workflow states response by matching the name case-insensitively.
     */
    String extractStateId(String json, String toStatus, String issueId) {
        String lowerTarget = toStatus.toLowerCase();
        int idx = 0;
        while (idx < json.length()) {
            int nameIdx = json.indexOf("\"name\":", idx);
            if (nameIdx == -1) break;
            int nameStart = json.indexOf("\"", nameIdx + 7) + 1;
            int nameEnd = findStringEnd(json, nameStart);
            if (nameStart < 1 || nameEnd == -1) break;
            String name = unescapeJson(json.substring(nameStart, nameEnd));
            if (name.equalsIgnoreCase(lowerTarget) || name.toLowerCase().contains(lowerTarget)) {
                // Find "id" field near this name — search backwards for the closest "id"
                int idIdx = json.lastIndexOf("\"id\":", nameIdx);
                if (idIdx != -1) {
                    int idStart = json.indexOf("\"", idIdx + 5) + 1;
                    int idEnd = findStringEnd(json, idStart);
                    if (idStart > 0 && idEnd != -1) {
                        return json.substring(idStart, idEnd);
                    }
                }
            }
            idx = nameEnd + 1;
        }
        throw new IllegalArgumentException("No Linear state named '" + toStatus + "' found for issue " + issueId);
    }

    /**
     * Extracts all label names from the Linear labels nodes response.
     */
    private String extractLabelNames(String json) {
        int nodesIdx = json.indexOf("\"nodes\":[");
        if (nodesIdx == -1) return null;
        int arrayStart = json.indexOf("[", nodesIdx);
        int arrayEnd = findArrayEnd(json, arrayStart);
        if (arrayStart == -1 || arrayEnd == -1) return null;
        String nodesArray = json.substring(arrayStart + 1, arrayEnd);
        if (nodesArray.isBlank()) return null;

        // Within the label nodes, extract all "name" values
        // But we need to avoid the state "name" which appears earlier in the JSON
        // The labels nodes are after the state section; we already have the sub-array
        StringBuilder names = new StringBuilder();
        int idx = 0;
        while (idx < nodesArray.length()) {
            int nameIdx = nodesArray.indexOf("\"name\":", idx);
            if (nameIdx == -1) break;
            int nameStart = nodesArray.indexOf("\"", nameIdx + 7) + 1;
            int nameEnd = findStringEnd(nodesArray, nameStart);
            if (nameStart < 1 || nameEnd == -1) break;
            String name = unescapeJson(nodesArray.substring(nameStart, nameEnd));
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
