package com.visa.nucleus.plugins.tracker;

import com.visa.nucleus.core.plugin.TrackerPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;

/**
 * TrackerPlugin implementation that integrates with Jira REST API v3.
 *
 * Configuration (application.yml):
 *   nucleus.tracker.jira.baseUrl  - e.g. https://visa.atlassian.net
 *   nucleus.tracker.jira.email    - service account email
 *   nucleus.tracker.jira.apiToken - read from env JIRA_API_TOKEN
 *
 * Authentication: HTTP Basic with base64(email:apiToken).
 */
public class JiraTrackerPlugin implements TrackerPlugin {

    private final String baseUrl;
    private final String authHeader;
    private final HttpClient httpClient;

    public JiraTrackerPlugin(String baseUrl, String email, String apiToken) {
        this.baseUrl = baseUrl.replaceAll("/$", "");
        String credentials = email + ":" + apiToken;
        this.authHeader = "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes());
        this.httpClient = HttpClient.newHttpClient();
    }

    /**
     * Fetches issue details and returns a formatted context string containing:
     * summary, plain-text description (converted from Atlassian Document Format),
     * acceptance criteria (if present), and labels.
     */
    @Override
    public String getIssueContext(String ticketId) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/issue/" + ticketId))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch issue " + ticketId + ": HTTP " + response.statusCode());
        }

        return parseIssueContext(response.body(), ticketId);
    }

    /**
     * Posts a comment to the specified Jira issue using ADF format.
     */
    @Override
    public void addComment(String ticketId, String comment) throws Exception {
        String body = buildCommentBody(comment);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/issue/" + ticketId + "/comment"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 201) {
            throw new RuntimeException("Failed to add comment to " + ticketId + ": HTTP " + response.statusCode());
        }
    }

    /**
     * Transitions the Jira issue to the specified status name.
     * First fetches available transitions to resolve the name to an ID.
     */
    @Override
    public void transitionStatus(String ticketId, String toStatus) throws Exception {
        String transitionId = findTransitionId(ticketId, toStatus);

        String body = "{\"transition\":{\"id\":\"" + transitionId + "\"}}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/issue/" + ticketId + "/transitions"))
                .header("Authorization", authHeader)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 204) {
            throw new RuntimeException("Failed to transition " + ticketId + " to '" + toStatus + "': HTTP " + response.statusCode());
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String findTransitionId(String ticketId, String toStatus) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/rest/api/3/issue/" + ticketId + "/transitions"))
                .header("Authorization", authHeader)
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to fetch transitions for " + ticketId + ": HTTP " + response.statusCode());
        }

        return extractTransitionId(response.body(), toStatus, ticketId);
    }

    /**
     * Parses the Jira issue JSON response into a formatted context string.
     * Uses simple string scanning to avoid a JSON library dependency.
     */
    String parseIssueContext(String json, String ticketId) {
        StringBuilder sb = new StringBuilder();
        sb.append("Ticket: ").append(ticketId).append("\n");

        String summary = extractJsonStringField(json, "summary");
        if (summary != null) {
            sb.append("Summary: ").append(summary).append("\n");
        }

        // Description is in ADF format under fields.description
        int descStart = json.indexOf("\"description\":{");
        if (descStart == -1) {
            descStart = json.indexOf("\"description\": {");
        }
        if (descStart != -1) {
            String descJson = extractObject(json, descStart + json.indexOf("{", descStart) - descStart);
            String plainText = adfToPlainText(descJson);
            if (!plainText.isBlank()) {
                String[] lines = plainText.split("\n");
                StringBuilder descSection = new StringBuilder();
                StringBuilder acSection = new StringBuilder();
                boolean inAc = false;
                for (String line : lines) {
                    String lower = line.toLowerCase();
                    if (lower.contains("acceptance criteria") || lower.contains("acceptance criterion")) {
                        inAc = true;
                    }
                    if (inAc) {
                        acSection.append(line).append("\n");
                    } else {
                        descSection.append(line).append("\n");
                    }
                }
                if (!descSection.toString().isBlank()) {
                    sb.append("Description:\n").append(descSection.toString().strip()).append("\n");
                }
                if (!acSection.toString().isBlank()) {
                    sb.append("Acceptance Criteria:\n").append(acSection.toString().strip()).append("\n");
                }
            }
        }

        // Labels
        int labelsIdx = json.indexOf("\"labels\":[");
        if (labelsIdx != -1) {
            int start = json.indexOf("[", labelsIdx);
            int end = json.indexOf("]", start);
            if (start != -1 && end != -1) {
                String labelsRaw = json.substring(start + 1, end);
                if (!labelsRaw.isBlank()) {
                    String labels = labelsRaw.replace("\"", "").replace(",", ", ").trim();
                    sb.append("Labels: ").append(labels).append("\n");
                }
            }
        }

        return sb.toString().strip();
    }

    /**
     * Converts Atlassian Document Format JSON to plain text by extracting
     * all "text" leaf nodes recursively.
     */
    String adfToPlainText(String adfJson) {
        if (adfJson == null || adfJson.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        int idx = 0;
        while (idx < adfJson.length()) {
            int textKey = adfJson.indexOf("\"text\":", idx);
            if (textKey == -1) break;
            int valueStart = adfJson.indexOf("\"", textKey + 7);
            if (valueStart == -1) break;
            int valueEnd = findStringEnd(adfJson, valueStart + 1);
            if (valueEnd == -1) break;
            String text = unescapeJson(adfJson.substring(valueStart + 1, valueEnd));
            if (!text.isBlank()) {
                sb.append(text);
            }
            idx = valueEnd + 1;
        }
        // Insert newlines at paragraph/heading boundaries
        return sb.toString()
                .replaceAll("(?<=[.!?])(?=[A-Z])", "\n")
                .strip();
    }

    private String buildCommentBody(String comment) {
        String escaped = escapeJson(comment);
        return "{"
                + "\"body\":{"
                + "\"type\":\"doc\","
                + "\"version\":1,"
                + "\"content\":[{"
                + "\"type\":\"paragraph\","
                + "\"content\":[{"
                + "\"type\":\"text\","
                + "\"text\":\"" + escaped + "\""
                + "}]"
                + "}]"
                + "}"
                + "}";
    }

    /** Extracts a simple string field value from JSON by key name. */
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

    /** Finds the closing quote of a JSON string starting at {@code from} (exclusive of opening quote). */
    private int findStringEnd(String json, int from) {
        for (int i = from; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') { i++; continue; }
            if (c == '"') return i;
        }
        return -1;
    }

    /** Extracts a JSON object substring starting at the '{' at or after {@code fromIdx}. */
    private String extractObject(String json, int fromIdx) {
        int start = json.indexOf("{", fromIdx);
        if (start == -1) return "";
        int depth = 0;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return json.substring(start);
    }

    /**
     * Parses the transitions response to find the ID matching toStatus (case-insensitive).
     * Expected JSON structure: {"transitions":[{"id":"...","name":"..."},...]}
     */
    String extractTransitionId(String json, String toStatus, String ticketId) {
        String lower = json.toLowerCase();
        String targetLower = toStatus.toLowerCase();
        int idx = 0;
        while (idx < lower.length()) {
            int nameIdx = lower.indexOf("\"name\":", idx);
            if (nameIdx == -1) break;
            int nameStart = json.indexOf("\"", nameIdx + 7) + 1;
            int nameEnd = findStringEnd(json, nameStart);
            if (nameEnd == -1) break;
            String name = json.substring(nameStart, nameEnd);
            if (name.equalsIgnoreCase(targetLower) || name.toLowerCase().contains(targetLower)) {
                // Find the "id" field in the same transition object — search backwards
                int idIdx = json.lastIndexOf("\"id\":", nameIdx);
                if (idIdx != -1) {
                    int idStart = json.indexOf("\"", idIdx + 5) + 1;
                    int idEnd = findStringEnd(json, idStart);
                    if (idEnd != -1) {
                        return json.substring(idStart, idEnd);
                    }
                }
            }
            idx = nameEnd + 1;
        }
        throw new IllegalArgumentException(
                "No transition named '" + toStatus + "' found for issue " + ticketId
                        + ". Available transitions response: " + json);
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
