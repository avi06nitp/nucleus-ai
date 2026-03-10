package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.AgentPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * AgentPlugin implementation that sends messages to the Anthropic Claude API.
 *
 * Configuration:
 *   nucleus.agent.anthropic.apiKey  — set via environment variable ANTHROPIC_API_KEY
 *   nucleus.agent.anthropic.model   — default: claude-sonnet-4-6
 */
public class ClaudeAgentPlugin implements AgentPlugin {

    static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    static final String API_URL = "https://api.anthropic.com/v1/messages";
    static final String API_VERSION = "2023-06-01";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;

    /** sessionId -> system prompt */
    private final Map<String, String> systemPrompts = new ConcurrentHashMap<>();
    /** sessionId -> ordered list of {role, content} message pairs */
    private final Map<String, List<String[]>> conversations = new ConcurrentHashMap<>();

    public ClaudeAgentPlugin() {
        this(System.getenv("ANTHROPIC_API_KEY"), DEFAULT_MODEL, HttpClient.newHttpClient());
    }

    // Package-private constructor for testing
    ClaudeAgentPlugin(String apiKey, String model, HttpClient httpClient) {
        this.apiKey = apiKey;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.httpClient = httpClient;
    }

    @Override
    public String getAgentType() {
        return "claude";
    }

    @Override
    public void initialize(AgentSession session, String issueContext) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "Anthropic API key is not configured. Set ANTHROPIC_API_KEY environment variable.");
        }

        String sessionId = session.getSessionId();
        String systemPrompt = buildSystemPrompt(issueContext, sessionId);
        systemPrompts.put(sessionId, systemPrompt);
        conversations.put(sessionId, new ArrayList<>());

        sendMessage(sessionId, "Begin. Review the issue context and confirm you are ready to start.");
    }

    @Override
    public void sendMessage(String sessionId, String message) throws Exception {
        String systemPrompt = systemPrompts.get(sessionId);
        if (systemPrompt == null) {
            throw new IllegalStateException("Session not initialized: " + sessionId);
        }

        List<String[]> history = conversations.get(sessionId);
        history.add(new String[]{"user", message});

        String requestBody = buildRequestBody(systemPrompt, history);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Content-Type", "application/json")
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "Anthropic API returned non-success status: " + response.statusCode()
                + " body: " + response.body());
        }

        String assistantReply = extractTextFromResponse(response.body());
        history.add(new String[]{"assistant", assistantReply});
    }

    private String buildSystemPrompt(String issueContext, String sessionId) {
        String ticketId = extractTicketId(sessionId);
        return "You are an autonomous coding agent working on: " + issueContext + "\n"
            + "Repository is at /workspace. You have full access to read and write files.\n"
            + "When done, create a commit with message: feat(" + ticketId + "): {summary}";
    }

    private String extractTicketId(String sessionId) {
        // Best-effort extraction of a ticket ID from sessionId (e.g. "PROJ-123-abc" -> "PROJ-123")
        if (sessionId == null) return "unknown";
        String[] parts = sessionId.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }
        return sessionId;
    }

    private String buildRequestBody(String systemPrompt, List<String[]> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"max_tokens\":4096,");
        sb.append("\"system\":\"").append(escapeJson(systemPrompt)).append("\",");
        sb.append("\"messages\":[");
        for (int i = 0; i < history.size(); i++) {
            String[] entry = history.get(i);
            if (i > 0) sb.append(",");
            sb.append("{\"role\":\"").append(entry[0]).append("\",")
              .append("\"content\":\"").append(escapeJson(entry[1])).append("\"}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // Minimal extraction of the first text block from the Anthropic response JSON
    String extractTextFromResponse(String responseBody) {
        String marker = "\"text\":\"";
        int start = responseBody.indexOf(marker);
        if (start == -1) return "";
        start += marker.length();
        StringBuilder result = new StringBuilder();
        for (int i = start; i < responseBody.length(); i++) {
            char c = responseBody.charAt(i);
            if (c == '"' && (i == start || responseBody.charAt(i - 1) != '\\')) break;
            result.append(c);
        }
        return result.toString().replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private String escapeJson(String value) {
        if (value == null) return "";
        return value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t");
    }
}
