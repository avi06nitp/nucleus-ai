package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * NotifierPlugin implementation that posts Block Kit messages to a Slack channel.
 *
 * Configuration (in priority order):
 *   1. SLACK_WEBHOOK_URL — Slack Incoming Webhook URL
 *   2. SLACK_BOT_TOKEN + SLACK_CHANNEL — Bot Token with target channel
 */
public class SlackNotifierPlugin implements NotifierPlugin {

    private static final String CHAT_POST_MESSAGE_URL = "https://slack.com/api/chat.postMessage";

    private final String webhookUrl;
    private final String botToken;
    private final String channel;
    private final HttpClient httpClient;

    public SlackNotifierPlugin() {
        this(
            System.getenv("SLACK_WEBHOOK_URL"),
            System.getenv("SLACK_BOT_TOKEN"),
            System.getenv("SLACK_CHANNEL"),
            HttpClient.newHttpClient()
        );
    }

    public SlackNotifierPlugin(String webhookUrl) {
        this(webhookUrl, null, null, HttpClient.newHttpClient());
    }

    // Package-private constructor for testing
    SlackNotifierPlugin(String webhookUrl, String botToken, String channel, HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.botToken = botToken;
        this.channel = channel;
        this.httpClient = httpClient;
    }

    @Override
    public void notify(String sessionId, String message, NotificationLevel level) throws Exception {
        String timestamp = Instant.now().toString();

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            postToWebhook(sessionId, message, level, timestamp);
        } else if (botToken != null && !botToken.isBlank()
                && channel != null && !channel.isBlank()) {
            postViaBotToken(sessionId, message, level, timestamp);
        } else {
            throw new IllegalStateException(
                "Slack is not configured. Set SLACK_WEBHOOK_URL, or both SLACK_BOT_TOKEN and SLACK_CHANNEL.");
        }
    }

    private void postToWebhook(String sessionId, String message,
                                NotificationLevel level, String timestamp) throws Exception {
        String payload = buildPayload(sessionId, message, level, timestamp, null);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        send(request);
    }

    private void postViaBotToken(String sessionId, String message,
                                  NotificationLevel level, String timestamp) throws Exception {
        String payload = buildPayload(sessionId, message, level, timestamp, channel);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(CHAT_POST_MESSAGE_URL))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + botToken)
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
        send(request);
    }

    private void send(HttpRequest request) throws Exception {
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "Slack API returned non-success status: " + response.statusCode()
                + " body: " + response.body());
        }
    }

    private String buildPayload(String sessionId, String message,
                                 NotificationLevel level, String timestamp, String channelOverride) {
        String color = colorIndicatorForLevel(level);
        String escapedSessionId = escapeJson(sessionId);
        String escapedMessage = escapeJson(message);
        String escapedLevel = level.name();
        String escapedTimestamp = escapeJson(timestamp);

        String channelField = channelOverride != null
            ? "\"channel\":\"" + escapeJson(channelOverride) + "\","
            : "";

        return "{"
            + channelField
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

    private String colorIndicatorForLevel(NotificationLevel level) {
        return switch (level) {
            case NEEDS_ATTENTION -> "warning";
            case READY_TO_MERGE  -> "good";
            case INFO            -> "normal";
        };
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
