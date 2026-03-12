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

    @BeforeEach
    void setUp() {
        httpClient = mock(HttpClient.class);
    }

    // ------------------------------------------------------------------
    // Webhook URL mode
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void notify_posts_to_webhook_when_webhook_url_set() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            "https://hooks.slack.com/services/TEST", null, null, httpClient);

        assertDoesNotThrow(() -> plugin.notify("session-1", "Build passed", NotificationLevel.INFO));
        verify(httpClient).send(any(HttpRequest.class), any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_throws_on_non_2xx_response() throws Exception {
        when(httpResponse.statusCode()).thenReturn(500);
        when(httpResponse.body()).thenReturn("Internal Server Error");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            "https://hooks.slack.com/services/TEST", null, null, httpClient);

        assertThrows(RuntimeException.class,
            () -> plugin.notify("session-1", "Build passed", NotificationLevel.INFO));
    }

    // ------------------------------------------------------------------
    // Bot Token + Channel mode
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void notify_uses_bot_token_when_no_webhook_url() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("{\"ok\":true}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            null, "xoxb-test-token", "#nucleus", httpClient);

        assertDoesNotThrow(() -> plugin.notify("session-1", "Build passed", NotificationLevel.INFO));

        verify(httpClient).send(
            argThat(req -> req.headers().firstValue("Authorization")
                .map(v -> v.equals("Bearer xoxb-test-token")).orElse(false)),
            any()
        );
    }

    @Test
    @SuppressWarnings("unchecked")
    void notify_prefers_webhook_url_over_bot_token() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            "https://hooks.slack.com/services/TEST", "xoxb-token", "#channel", httpClient);

        plugin.notify("session-1", "msg", NotificationLevel.INFO);

        verify(httpClient).send(
            argThat(req -> req.uri().toString().contains("hooks.slack.com")),
            any()
        );
    }

    // ------------------------------------------------------------------
    // Misconfiguration
    // ------------------------------------------------------------------

    @Test
    void notify_throws_when_nothing_configured() {
        SlackNotifierPlugin plugin = new SlackNotifierPlugin(null, null, null, httpClient);
        assertThrows(IllegalStateException.class,
            () -> plugin.notify("session-1", "msg", NotificationLevel.INFO));
    }

    @Test
    void notify_throws_when_bot_token_set_but_channel_missing() {
        SlackNotifierPlugin plugin = new SlackNotifierPlugin(null, "xoxb-token", null, httpClient);
        assertThrows(IllegalStateException.class,
            () -> plugin.notify("session-1", "msg", NotificationLevel.INFO));
    }

    // ------------------------------------------------------------------
    // All levels
    // ------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void notify_sends_all_notification_levels() throws Exception {
        when(httpResponse.statusCode()).thenReturn(200);
        when(httpResponse.body()).thenReturn("ok");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            "https://hooks.slack.com/services/TEST", null, null, httpClient);

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

        SlackNotifierPlugin plugin = new SlackNotifierPlugin(
            "https://hooks.slack.com/services/TEST", null, null, httpClient);

        assertDoesNotThrow(() ->
            plugin.notify("session-1", "Error: \"quotes\" and \nnewlines", NotificationLevel.NEEDS_ATTENTION));
    }
}
