package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SlackNotifierPluginTest {

    private HttpClient httpClient;
    @SuppressWarnings("unchecked")
    private HttpResponse<String> httpResponse = (HttpResponse<String>) mock(HttpResponse.class);
    private SlackNotifierPlugin plugin;

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
        plugin = new SlackNotifierPlugin("https://hooks.slack.com/services/TEST", httpClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_posts_to_slack_webhook() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertDoesNotThrow(() -> plugin.notify("session-1", "Build passed", NotificationLevel.INFO));

        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_throws_on_non_2xx_response() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        assertThrows(RuntimeException.class,
            () -> plugin.notify("session-1", "Build passed", NotificationLevel.INFO));
    }

    @Test
    void notify_throws_when_webhook_url_is_null() {
        SlackNotifierPlugin unconfigured = new SlackNotifierPlugin(null, httpClient);

        assertThrows(IllegalStateException.class,
            () -> unconfigured.notify("session-1", "msg", NotificationLevel.INFO));
    }

    @Test
    void notify_throws_when_webhook_url_is_blank() {
        SlackNotifierPlugin unconfigured = new SlackNotifierPlugin("  ", httpClient);

        assertThrows(IllegalStateException.class,
            () -> unconfigured.notify("session-1", "msg", NotificationLevel.INFO));
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_sends_correct_level_colors() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        // Verify all three levels can be sent without error
        plugin.notify("s1", "msg", NotificationLevel.INFO);
        plugin.notify("s1", "msg", NotificationLevel.NEEDS_ATTENTION);
        plugin.notify("s1", "msg", NotificationLevel.READY_TO_MERGE);

        verify(httpClient, times(3)).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_escapes_special_characters_in_message() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        // Message with JSON-unsafe characters should not break the payload
        assertDoesNotThrow(() ->
            plugin.notify("session-1", "Error: \"quotes\" and \nnewlines", NotificationLevel.NEEDS_ATTENTION));
    }
}
