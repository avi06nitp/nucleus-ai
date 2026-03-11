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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class OpenAIAgentPluginTest {

    private HttpClient httpClient;
    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse = (HttpResponse<String>) mock(HttpResponse.class);
    private OpenAIAgentPlugin plugin;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        plugin = new OpenAIAgentPlugin("test-api-key", OpenAIAgentPlugin.DEFAULT_MODEL, httpClient);
    }

    @Test
    void getAgentType_returnsOpenai() {
        assertEquals("openai", plugin.getAgentType());
    }

    @Test
    @SuppressWarnings("unchecked")
    void initialize_sendsFirstMessage() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn(buildOpenAIResponse("Ready to start."));
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
                .thenReturn(buildOpenAIResponse("Ready."))
                .thenReturn(buildOpenAIResponse("Done."));
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
        when(okResponse.body()).thenReturn(buildOpenAIResponse("Ready."));

        HttpResponse<String> errResponse = (HttpResponse<String>) mock(HttpResponse.class);
        when(errResponse.statusCode()).thenReturn(401);
        when(errResponse.body()).thenReturn("{\"error\":{\"message\":\"invalid key\"}}");

        doReturn(okResponse).doReturn(errResponse).when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "s1");
        plugin.initialize(session, "context");

        assertThrows(RuntimeException.class, () -> plugin.sendMessage(session.getSessionId(), "retry?"));
    }

    @Test
    void initialize_throwsWhenApiKeyMissing() {
        OpenAIAgentPlugin noKey = new OpenAIAgentPlugin(null, OpenAIAgentPlugin.DEFAULT_MODEL, httpClient);
        assertThrows(IllegalStateException.class,
            () -> noKey.initialize(new AgentSession("proj", "s"), "ctx"));
    }

    @Test
    void extractContentFromResponse_parsesContent() {
        String response = buildOpenAIResponse("Hello world");
        String text = plugin.extractContentFromResponse(response);
        assertEquals("Hello world", text);
    }

    @Test
    void extractContentFromResponse_returnsEmptyWhenNoContent() {
        assertEquals("", plugin.extractContentFromResponse("{}"));
    }

    @Test
    void extractFinishReason_returnsStop() {
        assertEquals("stop", plugin.extractFinishReason(buildOpenAIResponse("hi")));
    }

    @Test
    void extractFinishReason_returnsToolCalls() {
        String response = buildOpenAIToolCallResponse(
            "call_01", "write_file", "{\"path\":\"a.txt\",\"content\":\"hi\"}");
        assertEquals("tool_calls", plugin.extractFinishReason(response));
    }

    @Test
    void extractToolCallsList_parsesToolCall() {
        String response = buildOpenAIToolCallResponse(
            "call_01", "write_file", "{\\\"path\\\":\\\"a.txt\\\",\\\"content\\\":\\\"hi\\\"}");
        List<String[]> calls = plugin.extractToolCallsList(response);
        assertEquals(1, calls.size());
        assertEquals("call_01", calls.get(0)[0]);
        assertEquals("write_file", calls.get(0)[1]);
    }

    @Test
    void extractSimpleStringValue_decodesEscapeSequences() {
        String json = "{\"content\":\"line1\\nline2\"}";
        assertEquals("line1\nline2", plugin.extractSimpleStringValue(json, "content"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_executesToolCallAndContinues() throws Exception {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        OpenAIAgentPlugin pluginWithTools = new OpenAIAgentPlugin(
            "test-api-key", OpenAIAgentPlugin.DEFAULT_MODEL, httpClient, toolExecutor);

        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildOpenAIResponse("Ready."));

        // arguments are JSON-encoded inside the outer JSON string
        String arguments = "{\\\"path\\\":\\\"src/Main.java\\\",\\\"content\\\":\\\"public class Main {}\\\"}";
        HttpResponse<String> toolCallResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolCallResp.statusCode()).thenReturn(200);
        when(toolCallResp.body()).thenReturn(
            buildOpenAIToolCallResponse("call_01", "write_file", arguments));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildOpenAIResponse("Done."));

        doReturn(initResp).doReturn(toolCallResp).doReturn(endResp)
            .when(httpClient).send(any(HttpRequest.class), any());

        AgentSession session = new AgentSession("proj", "PROJ-42-abc");
        session.setWorktreePath("/tmp/workspace");
        pluginWithTools.initialize(session, "Implement feature X");
        pluginWithTools.sendMessage(session.getSessionId(), "Write the Main class");

        // init (1) + first sendMessage with tool_calls (1) + after tool result (1) = 3
        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
        verify(toolExecutor).writeFile("/tmp/workspace", "src/Main.java", "public class Main {}");
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendMessage_executesRunCommandTool() throws Exception {
        ToolExecutor toolExecutor = mock(ToolExecutor.class);
        when(toolExecutor.executeInContainer(anyString(), anyString())).thenReturn("On branch main");

        OpenAIAgentPlugin pluginWithTools = new OpenAIAgentPlugin(
            "test-api-key", OpenAIAgentPlugin.DEFAULT_MODEL, httpClient, toolExecutor);

        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildOpenAIResponse("Ready."));

        HttpResponse<String> toolCallResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolCallResp.statusCode()).thenReturn(200);
        when(toolCallResp.body()).thenReturn(
            buildOpenAIToolCallResponse("call_02", "run_command", "{\\\"command\\\":\\\"git status\\\"}"));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildOpenAIResponse("All done."));

        doReturn(initResp).doReturn(toolCallResp).doReturn(endResp)
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
        HttpResponse<String> initResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(initResp.statusCode()).thenReturn(200);
        when(initResp.body()).thenReturn(buildOpenAIResponse("Ready."));

        HttpResponse<String> toolCallResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(toolCallResp.statusCode()).thenReturn(200);
        when(toolCallResp.body()).thenReturn(
            buildOpenAIToolCallResponse("call_03", "write_file",
                "{\\\"path\\\":\\\"a.txt\\\",\\\"content\\\":\\\"hi\\\"}"));

        HttpResponse<String> endResp = (HttpResponse<String>) mock(HttpResponse.class);
        when(endResp.statusCode()).thenReturn(200);
        when(endResp.body()).thenReturn(buildOpenAIResponse("Acknowledged."));

        doReturn(initResp).doReturn(toolCallResp).doReturn(endResp)
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
    void buildRequestBody_handlesRawMessageEntries() {
        List<String[]> history = new java.util.ArrayList<>();
        history.add(new String[]{"user", "go"});
        history.add(new String[]{"", "{\"role\":\"assistant\",\"tool_calls\":[]}", "raw"});
        history.add(new String[]{"", "{\"role\":\"tool\",\"tool_call_id\":\"x\",\"content\":\"ok\"}", "raw"});

        String body = plugin.buildRequestBody("sys", history);
        assertTrue(body.contains("{\"role\":\"assistant\",\"tool_calls\":[]}"));
        assertTrue(body.contains("{\"role\":\"tool\",\"tool_call_id\":\"x\""));
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private String buildOpenAIResponse(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }

    /**
     * Builds a tool_calls response. argumentsEscaped should have inner quotes escaped with \\"
     * (they will appear as \" in the JSON string value).
     */
    private String buildOpenAIToolCallResponse(String callId, String functionName, String argumentsEscaped) {
        return "{\"id\":\"chatcmpl-2\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":null,\"tool_calls\":[{\"id\":\"" + callId + "\","
            + "\"type\":\"function\",\"function\":{\"name\":\"" + functionName + "\","
            + "\"arguments\":\"" + argumentsEscaped + "\"}}]},"
            + "\"finish_reason\":\"tool_calls\"}],"
            + "\"usage\":{\"prompt_tokens\":20,\"completion_tokens\":10,\"total_tokens\":30}}";
    }
}
