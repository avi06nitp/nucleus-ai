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
 * AgentPlugin implementation that sends messages to the OpenAI Chat Completions API,
 * with a full function-calling loop supporting write_file, read_file, list_files, and run_command.
 *
 * Configuration:
 *   nucleus.agent.openai.apiKey — set via environment variable OPENAI_API_KEY
 *   nucleus.agent.openai.model  — default: gpt-4o
 */
public class OpenAIAgentPlugin implements AgentPlugin {

    static final String DEFAULT_MODEL = "gpt-4o";
    static final String API_URL = "https://api.openai.com/v1/chat/completions";

    static final String TOOLS_JSON =
        "[{\"type\":\"function\",\"function\":{\"name\":\"write_file\"," +
        "\"description\":\"Write content to a file in the workspace\"," +
        "\"parameters\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\"},\"content\":{\"type\":\"string\"}}," +
        "\"required\":[\"path\",\"content\"]}}}," +
        "{\"type\":\"function\",\"function\":{\"name\":\"run_command\"," +
        "\"description\":\"Run a shell command in the workspace and return stdout+stderr\"," +
        "\"parameters\":{\"type\":\"object\",\"properties\":{" +
        "\"command\":{\"type\":\"string\"}},\"required\":[\"command\"]}}}," +
        "{\"type\":\"function\",\"function\":{\"name\":\"read_file\"," +
        "\"description\":\"Read a file from the workspace\"," +
        "\"parameters\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}}}," +
        "{\"type\":\"function\",\"function\":{\"name\":\"list_files\"," +
        "\"description\":\"List files in a directory\"," +
        "\"parameters\":{\"type\":\"object\",\"properties\":{" +
        "\"path\":{\"type\":\"string\"}}}}}]";

    private final String apiKey;
    private final String model;
    private final HttpClient httpClient;
    private final ToolExecutor toolExecutor;

    /** sessionId -> system prompt */
    private final Map<String, String> systemPrompts = new ConcurrentHashMap<>();
    /**
     * sessionId -> ordered list of message entries.
     * Simple messages: [role, content].
     * Raw JSON messages: ["", rawJsonObject, "raw"] — for assistant tool_calls and tool results.
     */
    private final Map<String, List<String[]>> conversations = new ConcurrentHashMap<>();
    /** sessionId -> worktree path on the host filesystem */
    private final Map<String, String> worktreePaths = new ConcurrentHashMap<>();

    public OpenAIAgentPlugin() {
        this(System.getenv("OPENAI_API_KEY"), DEFAULT_MODEL, HttpClient.newHttpClient(), null);
    }

    public OpenAIAgentPlugin(ToolExecutor toolExecutor) {
        this(System.getenv("OPENAI_API_KEY"), DEFAULT_MODEL, HttpClient.newHttpClient(), toolExecutor);
    }

    // Package-private constructor for testing (no ToolExecutor)
    OpenAIAgentPlugin(String apiKey, String model, HttpClient httpClient) {
        this(apiKey, model, httpClient, null);
    }

    // Package-private constructor for testing (with ToolExecutor)
    OpenAIAgentPlugin(String apiKey, String model, HttpClient httpClient, ToolExecutor toolExecutor) {
        this.apiKey = apiKey;
        this.model = model != null ? model : DEFAULT_MODEL;
        this.httpClient = httpClient;
        this.toolExecutor = toolExecutor;
    }

    @Override
    public String getAgentType() {
        return "openai";
    }

    @Override
    public void initialize(AgentSession session, String issueContext) throws Exception {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                "OpenAI API key is not configured. Set OPENAI_API_KEY environment variable.");
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

        // Function-calling loop: keep sending until finish_reason == "stop"
        while (true) {
            String requestBody = buildRequestBody(systemPrompt, history);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new RuntimeException(
                    "OpenAI API returned non-success status: " + response.statusCode()
                    + " body: " + response.body());
            }

            String responseBody = response.body();
            String finishReason = extractFinishReason(responseBody);

            if ("tool_calls".equals(finishReason)) {
                // Store the raw assistant message (with tool_calls) in history
                String assistantMessage = extractAssistantMessage(responseBody);
                history.add(new String[]{"", assistantMessage, "raw"});

                // Extract and execute each tool call, add a tool result message per call
                List<String[]> toolCalls = extractToolCallsList(responseBody);
                for (String[] call : toolCalls) {
                    String callId = call[0];
                    String name = call[1];
                    String arguments = call[2];
                    String result = executeTool(sessionId, name, arguments);
                    String toolMsg = "{\"role\":\"tool\",\"tool_call_id\":\""
                        + escapeJson(callId) + "\",\"content\":\""
                        + escapeJson(result) + "\"}";
                    history.add(new String[]{"", toolMsg, "raw"});
                }
            } else {
                // finish_reason == "stop" — normal response
                String assistantReply = extractContentFromResponse(responseBody);
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
        sb.append("\"tools\":").append(TOOLS_JSON).append(",");
        sb.append("\"messages\":[");
        sb.append("{\"role\":\"system\",\"content\":\"").append(escapeJson(systemPrompt)).append("\"}");
        for (String[] entry : history) {
            sb.append(",");
            boolean isRaw = entry.length > 2 && "raw".equals(entry[2]);
            if (isRaw) {
                sb.append(entry[1]);
            } else {
                sb.append("{\"role\":\"").append(entry[0]).append("\",")
                  .append("\"content\":\"").append(escapeJson(entry[1])).append("\"}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Tool execution
    // -------------------------------------------------------------------------

    private String executeTool(String sessionId, String name, String argumentsJson) {
        if (toolExecutor == null) {
            return "Error: tool executor not configured";
        }
        String worktreePath = worktreePaths.getOrDefault(sessionId, "/workspace");
        try {
            switch (name) {
                case "write_file": {
                    String path = extractSimpleStringValue(argumentsJson, "path");
                    String content = extractSimpleStringValue(argumentsJson, "content");
                    toolExecutor.writeFile(worktreePath, path, content);
                    return "File written: " + path;
                }
                case "run_command": {
                    String command = extractSimpleStringValue(argumentsJson, "command");
                    return toolExecutor.executeInContainer(sessionId, command);
                }
                case "read_file": {
                    String path = extractSimpleStringValue(argumentsJson, "path");
                    return toolExecutor.readFile(worktreePath, path);
                }
                case "list_files": {
                    String path = extractSimpleStringValue(argumentsJson, "path");
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

    // -------------------------------------------------------------------------
    // JSON parsing helpers
    // -------------------------------------------------------------------------

    /** Extracts finish_reason from the first choice in the OpenAI response. */
    String extractFinishReason(String responseBody) {
        String marker = "\"finish_reason\":\"";
        int start = responseBody.indexOf(marker);
        if (start == -1) return "";
        start += marker.length();
        int end = responseBody.indexOf("\"", start);
        if (end == -1) return "";
        return responseBody.substring(start, end);
    }

    /**
     * Extracts the raw assistant message object from choices[0].message.
     * Returns the full JSON object string, e.g. {"role":"assistant","content":null,"tool_calls":[...]}.
     */
    String extractAssistantMessage(String responseBody) {
        String marker = "\"message\":";
        int start = responseBody.indexOf(marker);
        if (start == -1) return "{}";
        start += marker.length();
        // skip whitespace
        while (start < responseBody.length() && responseBody.charAt(start) != '{') start++;
        String obj = extractBalanced(responseBody, start, '{', '}');
        return obj != null ? obj : "{}";
    }

    /**
     * Extracts all tool calls from the response.
     * Returns list of [id, name, argumentsJson] arrays.
     * argumentsJson is the parsed JSON string from the "arguments" field.
     */
    List<String[]> extractToolCallsList(String responseBody) {
        List<String[]> calls = new ArrayList<>();
        String marker = "\"tool_calls\":[";
        int start = responseBody.indexOf(marker);
        if (start == -1) return calls;
        int arrayStart = start + marker.length() - 1;
        String array = extractBalanced(responseBody, arrayStart, '[', ']');
        if (array == null) return calls;

        int pos = 1;
        while (pos < array.length()) {
            char c = array.charAt(pos);
            if (c == '{') {
                String obj = extractBalanced(array, pos, '{', '}');
                if (obj == null) break;
                String id = extractSimpleStringValue(obj, "id");
                // function object: {"name":"...","arguments":"..."}
                String functionObj = extractJsonObjectField(obj, "function");
                String name = extractSimpleStringValue(functionObj, "name");
                String arguments = extractSimpleStringValue(functionObj, "arguments");
                calls.add(new String[]{id, name, arguments});
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

    /** Extracts a nested JSON object value for a given key (e.g. "function":{...}). */
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
     * Works for string-typed fields (path, content, command, id, name, arguments, etc.).
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

    // Minimal extraction of the assistant content from the OpenAI response JSON
    String extractContentFromResponse(String responseBody) {
        String marker = "\"content\":\"";
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
