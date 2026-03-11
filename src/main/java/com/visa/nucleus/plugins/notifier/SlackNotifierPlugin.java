package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * NotifierPlugin implementation that posts Block Kit messages to a Slack channel
 * via an Incoming Webhook URL.
 *
 * Configuration:
 *   SLACK_WEBHOOK_URL — Slack Incoming Webhook URL
 */
public class SlackNotifierPlugin implements NotifierPlugin {

    private final String webhookUrl;
    private final HttpClient httpClient;

    public SlackNotifierPlugin() {
        this(System.getenv("SLACK_WEBHOOK_URL"), HttpClient.newHttpClient());
    }

    // Package-private constructor for testing
    SlackNotifierPlugin(String webhookUrl, HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    @Override
    public void notify(String sessionId, String message, NotificationLevel level) throws Exception {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                "Slack webhook URL is not configured. Set SLACK_WEBHOOK_URL environment variable.");
        }

        String timestamp = Instant.now().toString();
        String payload = buildPayload(sessionId, message, level, timestamp);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "Slack webhook returned non-success status: " + response.statusCode()
                + " body: " + response.body());
        }
    }

    private String colorIndicatorForLevel(NotificationLevel level) {
        return switch (level) {
            case NEEDS_ATTENTION -> "warning";
            case READY_TO_MERGE  -> "good";
            case INFO            -> "normal";
        };
    }

    private String buildPayload(String sessionId, String message,
                                NotificationLevel level, String timestamp) {
        String color = colorIndicatorForLevel(level);
        String escapedSessionId = escapeJson(sessionId);
        String escapedMessage = escapeJson(message);
        String escapedLevel = level.name();
        String escapedTimestamp = escapeJson(timestamp);

        return "{"
            + "\"attachments\":[{"
            +   "\"color\":\"" + color + "\","
            +   "\"blocks\":["
            +     "{"
            +       "\"type\":\"section\","
            +       "\"text\":{"
            +         "\"type\":\"mrkdwn\","
            +         "\"text\":\"*Nucleus AI* \\u2014 Session `" + escapedSessionId + "`\\n" + escapedMessage + "\""
            +       "}"
            +     "},"
            +     "{"
            +       "\"type\":\"context\","
            +       "\"elements\":[{"
            +         "\"type\":\"mrkdwn\","
            +         "\"text\":\"Level: *" + escapedLevel + "* | " + escapedTimestamp + "\""
            +       "}]"
            +     "}"
            +   "]"
            + "}]}";
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
