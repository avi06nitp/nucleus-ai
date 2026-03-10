package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;

/**
 * NotifierPlugin implementation that posts adaptive card messages to a Microsoft Teams
 * channel via an Incoming Webhook connector.
 *
 * Configuration:
 *   nucleus.notifier.teams.webhookUrl — set via environment variable TEAMS_WEBHOOK_URL
 */
public class TeamsNotifierPlugin implements NotifierPlugin {

    private final String webhookUrl;
    private final HttpClient httpClient;

    public TeamsNotifierPlugin() {
        this(System.getenv("TEAMS_WEBHOOK_URL"), HttpClient.newHttpClient());
    }

    // Package-private constructor for testing
    TeamsNotifierPlugin(String webhookUrl, HttpClient httpClient) {
        this.webhookUrl = webhookUrl;
        this.httpClient = httpClient;
    }

    @Override
    public void notify(String sessionId, String message, NotificationLevel level) throws Exception {
        if (webhookUrl == null || webhookUrl.isBlank()) {
            throw new IllegalStateException(
                "Teams webhook URL is not configured. Set TEAMS_WEBHOOK_URL environment variable.");
        }

        String themeColor = colorForLevel(level);
        String timestamp = Instant.now().toString();

        String payload = buildPayload(sessionId, message, level, themeColor, timestamp);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(webhookUrl))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new RuntimeException(
                "Teams webhook returned non-success status: " + response.statusCode()
                + " body: " + response.body());
        }
    }

    private String colorForLevel(NotificationLevel level) {
        switch (level) {
            case NEEDS_ATTENTION: return "FF8C00";
            case READY_TO_MERGE:  return "00C851";
            case INFO:
            default:              return "0076D7";
        }
    }

    private String buildPayload(String sessionId, String message,
                                NotificationLevel level, String themeColor, String timestamp) {
        return "{"
            + "\"@type\":\"MessageCard\","
            + "\"@context\":\"http://schema.org/extensions\","
            + "\"themeColor\":\"" + themeColor + "\","
            + "\"summary\":\"Nucleus AI Update\","
            + "\"sections\":[{"
            +   "\"activityTitle\":\"Nucleus AI \u2014 Agent Update\","
            +   "\"activitySubtitle\":\"Session: " + escapeJson(sessionId) + "\","
            +   "\"activityText\":\"" + escapeJson(message) + "\","
            +   "\"facts\":["
            +     "{\"name\":\"Level\",\"value\":\"" + level.name() + "\"},"
            +     "{\"name\":\"Time\",\"value\":\"" + escapeJson(timestamp) + "\"}"
            +   "]"
            + "}]"
            + "}";
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
