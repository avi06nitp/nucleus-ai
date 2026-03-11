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
 * AgentPlugin implementation that sends messages to the Anthropic Claude API,
 * with a full tool-use loop supporting write_file, read_file, list_files, and run_command.
 *
 * Configuration:
 *   nucleus.agent.anthropic.apiKey  — set via environment variable ANTHROPIC_API_KEY
 *   nucleus.agent.anthropic.model   — default: claude-sonnet-4-6
 */
public class ClaudeAgentPlugin implements AgentPlugin {

    static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    static final String API_URL = "https://api.anthropic.com/v1/messages";
    static final String API_VERSION = "2023-06-01";

    static final String TOOLS_JSON =
        "[{\"name\":\"write_file\"," +
        "\"description\":\"Write content to a file in the workspace\"," +
        "\"input_schema\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}," +
        "\"required\":[\"path\",\"content\"]}}," +
        "{\"name\":\"run_command\"," +
        "\"description\":\"Run a shell command in the workspace and return stdout+stderr\"," +
        "\"input_schema\":{\"type\":\"object\",\"properties\":{" +
        "\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}}," +
        "{\"name\":\"read_file\"," +
        "\"description\":\"Read a file from the workspace\"," +
        "\"input_schema\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}," +
        "{\"name\":\"list_files\"," +
        "\"description\":\"List files in a directory\"," +
        "\"input_schema\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\",\"default\":\".\"}}}}]";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ToolExecutor toolExecutor;

    /** sessionId -> system prompt */
    private final Map<String, String> systemPrompts = new ConcurrentHashMap<>();
    /** sessionId -> ordered list of message entries: [role, content] or [role, rawJson, "raw"] */
    private final Map<String, List<String[]>> conversations = new ConcurrentHashMap<>();
    /** sessionId -> worktree path on the host filesystem */
    private final Map<String, String> worktreePaths = new ConcurrentHashMap<>();

    public ClaudeAgentPlugin() {
        this(System.getenv("ANTHROPIC_API_KEY"), DEFAULT_MODEL, HttpClient.newHttpClient(), null);
    }

    public ClaudeAgentPlugin(ToolExecutor toolExecutor) {
        this(System.getenv("ANTHROPIC_API_KEY"), DEFAULT_MODEL, HttpClient.newHttpClient(), toolExecutor);
    }

    // Package-private constructor for testing (no ToolExecutor)
    ClaudeAgentPlugin(String apiKey, String model, HttpClient httpClient) {
        this(apiKey, model, httpClient, null);
    }

    // Package-private constructor for testing (with ToolExecutor)
    ClaudeAgentPlugin(String apiKey, String model, HttpClient httpClient, ToolExecutor toolExecutor) {
        this.apiKey = apiKey;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.httpClient = httpClient;
        this.toolExecutor = toolExecutor;
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
        if (session.getWorktreePath() != null) {
            worktreePaths.put(sessionId, session.getWorktreePath());
        }

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

        // Tool-use loop: keep sending until stop_reason == "end_turn"
        while (true) {
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

            String responseBody = response.body();
            String stopReason = extractStopReason(responseBody);

            if ("tool_use".equals(stopReason)) {
                // Store the full assistant content array in history
                String contentArray = extractContentArray(responseBody);
                history.add(new String[]{"assistant", contentArray, "raw"});

                // Extract tool calls, execute them, and build result array
                List<String[]> toolCalls = extractToolUseCalls(contentArray);
                List<String> results = new ArrayList<>();
                for (String[] call : toolCalls) {
                    results.add(executeTool(sessionId, call[1], call[2]));
                }

                String toolResultArray = buildToolResultArray(toolCalls, results);
                history.add(new String[]{"user", toolResultArray, "raw"});
            } else {
                // end_turn — normal text response
                String assistantReply = extractTextFromResponse(responseBody);
                history.add(new String[]{"assistant", assistantReply});
                break;
            }
        }
    }

    private String buildSystemPrompt(String issueContext, String sessionId) {
        String ticketId = extractTicketId(sessionId);
        return "You are an autonomous coding agent working on: " + issueContext + "\n"
            + "Repository is at /workspace. You have full access to read and write files.\n"
            + "When done, create a commit with message: feat(" + ticketId + "): {summary}";
    }

    private String extractTicketId(String sessionId) {
        if (sessionId == null) return "unknown";
        String[] parts = sessionId.split("-");
        if (parts.length >= 2) {
            return parts[0] + "-" + parts[1];
        }
        return sessionId;
    }

    String buildRequestBody(String systemPrompt, List<String[]> history) {
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(escapeJson(model)).append("\",");
        sb.append("\"max_tokens\":4096,");
        sb.append("\"system\":\"").append(escapeJson(systemPrompt)).append("\",");
        sb.append("\"tools\":").append(TOOLS_JSON).append(",");
        sb.append("\"messages\":[");
        for (int i = 0; i < history.size(); i++) {
            String[] entry = history.get(i);
            if (i > 0) sb.append(",");
            boolean isRaw = entry.length > 2 && "raw".equals(entry[2]);
            sb.append("{\"role\":\"").append(entry[0]).append("\",\"content\":");
            if (isRaw) {
                sb.append(entry[1]);
            } else {
                sb.append("\"").append(escapeJson(entry[1])).append("\"");
            }
            sb.append("}");
        }
        sb.append("]}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    private String executeTool(String sessionId, String name, String inputJson) {
        if (toolExecutor == null) {
            return "Error: tool executor not configured";
        }
        String worktreePath = worktreePaths.getOrDefault(sessionId, "/workspace");
        try {
            switch (name) {
                case "write_file": {
                    String path = extractSimpleStringValue(inputJson, "path");
                    String content = extractSimpleStringValue(inputJson, "content");
                    toolExecutor.writeFile(worktreePath, path, content);
                    return "File written: " + path;
                }
                case "run_command": {
                    String command = extractSimpleStringValue(inputJson, "command");
                    return toolExecutor.executeInContainer(sessionId, command);
                }
                case "read_file": {
                    String path = extractSimpleStringValue(inputJson, "path");
                    return toolExecutor.readFile(worktreePath, path);
                }
                case "list_files": {
                    String path = extractSimpleStringValue(inputJson, "path");
                    if (path.isEmpty()) path = ".";
                    return toolExecutor.listFiles(worktreePath, path);
                }
                default:
                    return "Error: unknown tool: " + name;
            }
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    private String buildToolResultArray(List<String[]> toolCalls, List<String> results) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < toolCalls.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("{\"type\":\"tool_result\",\"tool_use_id\":\"")
              .append(escapeJson(toolCalls.get(i)[0])).append("\",")
              .append("\"content\":\"").append(escapeJson(results.get(i))).append("\"}");
        }
        sb.append("]");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // JSON parsing helpers
    // -------------------------------------------------------------------------

    /** Extracts stop_reason value from Anthropic response JSON. */
    String extractStopReason(String responseBody) {
        String marker = "\"stop_reason\":\"";
        int start = responseBody.indexOf(marker);
        if (start == -1) return "";
        start += marker.length();
        int end = responseBody.indexOf("\"", start);
        if (end == -1) return "";
        return responseBody.substring(start, end);
    }

    /** Extracts the top-level content array from the Anthropic response. */
    String extractContentArray(String responseBody) {
        String marker = "\"content\":[";
        int start = responseBody.indexOf(marker);
        if (start == -1) return "[]";
        int arrayStart = start + marker.length() - 1;
        String arr = extractBalanced(responseBody, arrayStart, '[', ']');
        return arr != null ? arr : "[]";
    }

    /**
     * Parses all tool_use objects from the content array.
     * Returns list of [id, name, inputJson] arrays.
     */
    List<String[]> extractToolUseCalls(String contentArray) {
        List<String[]> calls = new ArrayList<>();
        int pos = 1;
        while (pos < contentArray.length()) {
            char c = contentArray.charAt(pos);
            if (c == '{') {
                String obj = extractBalanced(contentArray, pos, '{', '}');
                if (obj == null) break;
                if (obj.contains("\"type\":\"tool_use\"")) {
                    String id = extractSimpleStringValue(obj, "id");
                    String name = extractSimpleStringValue(obj, "name");
                    String inputJson = extractJsonObjectField(obj, "input");
                    calls.add(new String[]{id, name, inputJson});
                }
                pos += obj.length();
            } else {
                pos++;
            }
        }
        return calls;
    }

    /** Extracts a balanced substring starting at start using openChar/closeChar. */
    private String extractBalanced(String json, int start, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) { escaped = false; continue; }
            if (c == '\\' && inString) { escaped = true; continue; }
            if (c == '"') { inString = !inString; continue; }
            if (inString) continue;
            if (c == openChar) depth++;
            else if (c == closeChar) {
                depth--;
                if (depth == 0) return json.substring(start, i + 1);
            }
        }
        return null;
    }

    /** Extracts a nested JSON object value for a given key (e.g. "input":{...}). */
    private String extractJsonObjectField(String json, String key) {
        String marker = "\"" + key + "\":{";
        int start = json.indexOf(marker);
        if (start == -1) return "{}";
        int objStart = start + marker.length() - 1;
        String obj = extractBalanced(json, objStart, '{', '}');
        return obj != null ? obj : "{}";
    }

    /**
     * Extracts a simple string value for a given key, processing JSON escape sequences.
     * Works for string-typed fields (path, content, command, id, name, etc.).
     */
    String extractSimpleStringValue(String json, String key) {
        String marker = "\"" + key + "\":\"";
        int start = json.indexOf(marker);
        if (start == -1) return "";
        start += marker.length();
        StringBuilder result = new StringBuilder();
        boolean escaped = false;
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n': result.append('\n'); break;
                    case 'r': result.append('\r'); break;
                    case 't': result.append('\t'); break;
                    case '"': result.append('"'); break;
                    case '\\': result.append('\\'); break;
                    default: result.append('\\').append(c); break;
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                break;
            } else {
                result.append(c);
            }
        }
        return result.toString();
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
