package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.AgentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

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

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private String buildAnthropicResponse(String text) {
        return "{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\","
            + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
            + "\"model\":\"" + ClaudeAgentPlugin.DEFAULT_MODEL + "\","
            + "\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":5}}";
    }
}
