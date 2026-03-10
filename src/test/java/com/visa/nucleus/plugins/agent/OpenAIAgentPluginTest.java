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

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private String buildOpenAIResponse(String content) {
        return "{\"id\":\"chatcmpl-1\",\"object\":\"chat.completion\","
            + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\","
            + "\"content\":\"" + content + "\"},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5,\"total_tokens\":15}}";
    }
}
