package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.AgentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ClaudeAgentPluginTest {

    private HttpClient httpClient;
    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse = (HttpResponse<String>) mock(HttpResponse.class);
    private ClaudeAgentPlugin plugin;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        plugin = new ClaudeAgentPlugin("test-api-key", ClaudeAgentPlugin.DEFAULT_MODEL, httpClient);
    }

    @Test
    void getAgentType_returnsClaude() {
        assertEquals("claude", plugin.getAgentType());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initialize_sendsFirstMessage() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(buildAnthropicResponse("Ready to start."));
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "PROJ-42-abc");
        plugin.initialize(session, "Implement feature X");

        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_appendsToConversationHistory() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body())
                .thenReturn(buildAnthropicResponse("Ready."))
                .thenReturn(buildAnthropicResponse("Done."));
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "PROJ-10-xyz");
        plugin.initialize(session, "Fix bug Y");
        plugin.sendMessage(session.getSessionId(), "What is the status?");

        verify(httpClient, times(2)).send(any(HttpRequest.class), any());
    }

    @Test
    void sendMessage_throwsWhenSessionNotInitialized() {
        assertThrows(IllegalStateException.class,
            () -> plugin.sendMessage("nonexistent-session", "hello"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_throwsOnNonSuccessHttpStatus() throws Exception {
        HttpResponse<String> okResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(okResponse.statusCode()).thenReturn(200);
        when(okResponse.body()).thenReturn(buildAnthropicResponse("Ready."));

        HttpResponse<String> errResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(errResponse.statusCode()).thenReturn(429);
        when(errResponse.body()).thenReturn("{\"error\":\"rate_limited\"}");

        doReturn(okResponse).doReturn(errResponse).when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "s1");
        plugin.initialize(session, "context");

        assertThrows(RuntimeException.class, () -> plugin.sendMessage(session.getSessionId(), "retry?"));
    }

    @Test
    void initialize_throwsWhenApiKeyMissing() {
        ClaudeAgentPlugin noKey = new ClaudeAgentPlugin(null, ClaudeAgentPlugin.DEFAULT_MODEL, httpClient);
        assertThrows(IllegalStateException.class,
            () -> noKey.initialize(new AgentSession("proj", "s"), "ctx"));
    }

    @Test
    void extractTextFromResponse_parsesFirstTextBlock() {
        String response = buildAnthropicResponse("Hello world");
        String text = plugin.extractTextFromResponse(response);
        assertEquals("Hello world", text);
    }

    @Test
    void extractTextFromResponse_returnsEmptyWhenNoTextBlock() {
        assertEquals("", plugin.extractTextFromResponse("{}"));
    }

    @Test
    void extractStopReason_returnsEndTurn() {
        String response = buildAnthropicResponse("Hello");
        assertEquals("end_turn", plugin.extractStopReason(response));
    }

    @Test
    void extractStopReason_returnsToolUse() {
        String response = buildAnthropicToolUseResponse(
            "toolu_01", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}");
        assertEquals("tool_use", plugin.extractStopReason(response));
    }

    @Test
    void extractContentArray_returnsArrayFromResponse() {
        String response = buildAnthropicToolUseResponse(
            "toolu_01", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}");
        String arr = plugin.extractContentArray(response);
        assertTrue(arr.startsWith("["));
        assertTrue(arr.endsWith("]"));
        assertTrue(arr.contains("tool_use"));
    }

    @Test
    void extractToolUseCalls_parsesToolUseBlock() {
        String contentArray = "[{\"type\":\"tool_use\",\"id\":\"toolu_01\"," +
            "\"name\":\"write_file\",\"input\":{\"path\":\"src/A.java\",\"content\":\"class A {}\"}}]";
        List<String[]> calls = plugin.extractToolUseCalls(contentArray);
        assertEquals(1, calls.size());
        assertEquals("toolu_01", calls.get(0)[0]);
        assertEquals("write_file", calls.get(0)[1]);
    }

    @Test
    void extractToolUseCalls_ignoresNonToolUseBlocks() {
        String contentArray = "[{\"type\":\"text\",\"text\":\"hello\"}," +
            "{\"type\":\"tool_use\",\"id\":\"toolu_02\",\"name\":\"run_command\"," +
            "\"input\":{\"command\":\"git status\"}}]";
        List<String[]> calls = plugin.extractToolUseCalls(contentArray);
        assertEquals(1, calls.size());
        assertEquals("run_command", calls.get(0)[1]);
    }

    @Test
    void extractSimpleStringValue_decodesEscapeSequences() {
        String json = "{\"content\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", plugin.extractSimpleStringValue(json, "content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_executesToolUseAndContinues() throws Exception {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        ClaudeAgentPlugin pluginWithTools = new ClaudeAgentPlugin(
            "test-api-key", ClaudeAgentPlugin.DEFAULT_MODEL, httpClient, toolExecutor);

        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildAnthropicResponse("Ready."));

        HttpResponse<String> toolUseResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolUseResp.statusCode()).thenReturn(200);
        when(toolUseResp.body()).thenReturn(buildAnthropicToolUseResponse(
            "toolu_01", "write_file",
            "{\"path\":\"src/Main.java\",\"content\":\"public class Main {}\"}"));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildAnthropicResponse("Done."));

        doReturn(initResp).doReturn(toolUseResp).doReturn(endResp)
            .when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "PROJ-42-abc");
        session.setWorktreePath("/tmp/workspace");
        pluginWithTools.initialize(session, "Implement feature X");
        pluginWithTools.sendMessage(session.getSessionId(), "Write the Main class");

        // init (1) + first sendMessage with tool_use (1) + after tool result (1) = 3
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
        verify(toolExecutor).writeFile("/tmp/workspace", "src/Main.java", "public class Main {}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_executesRunCommandTool() throws Exception {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(toolExecutor.executeInContainer(anyString(), anyString())).thenReturn("nothing to commit");

        ClaudeAgentPlugin pluginWithTools = new ClaudeAgentPlugin(
            "test-api-key", ClaudeAgentPlugin.DEFAULT_MODEL, httpClient, toolExecutor);

        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildAnthropicResponse("Ready."));

        HttpResponse<String> toolUseResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolUseResp.statusCode()).thenReturn(200);
        when(toolUseResp.body()).thenReturn(buildAnthropicToolUseResponse(
            "toolu_02", "run_command", "{\"command\":\"git status\"}"));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildAnthropicResponse("All done."));

        doReturn(initResp).doReturn(toolUseResp).doReturn(endResp)
            .when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "PROJ-99-cmd");
        session.setWorktreePath("/tmp/workspace");
        pluginWithTools.initialize(session, "Check git status");
        pluginWithTools.sendMessage(session.getSessionId(), "Run git status");

        verify(toolExecutor).executeInContainer(session.getSessionId(), "git status");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_returnsErrorResultWhenToolExecutorIsNull() throws Exception {
        // Plugin with null ToolExecutor should not throw; it returns an error result to the model
        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildAnthropicResponse("Ready."));

        HttpResponse<String> toolUseResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolUseResp.statusCode()).thenReturn(200);
        when(toolUseResp.body()).thenReturn(buildAnthropicToolUseResponse(
            "toolu_03", "write_file",
            "{\"path\":\"a.txt\",\"content\":\"hi\"}"));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildAnthropicResponse("Acknowledged."));

        doReturn(initResp).doReturn(toolUseResp).doReturn(endResp)
            .when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "s-null");
        plugin.initialize(session, "context");
        assertDoesNotThrow(() -> plugin.sendMessage(session.getSessionId(), "write something"));

        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    void buildRequestBody_includesToolsArray() {
        List<String[]> history = new java.util.ArrayList<>();
        history.add(new String[]{"user", "hello"});
        String body = plugin.buildRequestBody("sys", history);
        assertTrue(body.contains("\"tools\":["));
        assertTrue(body.contains("write_file"));
        assertTrue(body.contains("run_command"));
        assertTrue(body.contains("read_file"));
        assertTrue(body.contains("list_files"));
    }

    @Test
    void buildRequestBody_handlesRawContentEntries() {
        List<String[]> history = new java.util.ArrayList<>();
        history.add(new String[]{"user", "go"});
        history.add(new String[]{"assistant", "[{\"type\":\"tool_use\"}]", "raw"});
        history.add(new String[]{"user", "[{\"type\":\"tool_result\",\"tool_use_id\":\"x\",\"content\":\"ok\"}]", "raw"});

        String body = plugin.buildRequestBody("sys", history);
        // raw entries should not be double-escaped
        assertTrue(body.contains("[{\"type\":\"tool_use\"}]"));
        assertTrue(body.contains("[{\"type\":\"tool_result\""));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private String buildAnthropicResponse(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
            + "\"model\":\"" + ClaudeAgentPlugin.DEFAULT_MODEL + "\","
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }

    private String buildAnthropicToolUseResponse(String toolId, String toolName, String inputJson) {
        return "{\"id\":\"msg_2\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"tool_use\",\"id\":\"" + toolId + "\","
            + "\"name\":\"" + toolName + "\",\"input\":" + inputJson + "}],"
            + "\"model\":\"" + ClaudeAgentPlugin.DEFAULT_MODEL + "\","
            + "\"stop_reason\":\"tool_use\",\"usage\":{\"input_tokens\":20,\"output_tokens\":10}}";
    }
}
