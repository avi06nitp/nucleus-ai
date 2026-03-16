package com.visa.nucleus.config;

import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import com.visa.nucleus.plugins.notifier.DesktopNotifierPlugin;
import com.visa.nucleus.plugins.notifier.SlackNotifierPlugin;
import com.visa.nucleus.plugins.notifier.TeamsNotifierPlugin;
import com.visa.nucleus.plugins.tracker.JiraTrackerPlugin;
import com.visa.nucleus.plugins.workspace.GitWorktreePlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Registers plugin implementations as Spring beans so they can be injected into
 * OrchestratorService and other components.
 */
@Configuration
public class PluginConfig {

    @Bean
    public TrackerPlugin trackerPlugin(
            @Value("${nucleus.tracker.jira.baseUrl:https://placeholder.atlassian.net}") String baseUrl,
            @Value("${nucleus.tracker.jira.email:placeholder@example.com}") String email,
            @Value("${nucleus.tracker.jira.apiToken:${JIRA_API_TOKEN:placeholder}}") String apiToken) {
        return new JiraTrackerPlugin(baseUrl, email, apiToken);
    }

    @Bean
    public WorkspacePlugin workspacePlugin() {
        return new GitWorktreePlugin();
    }

    @Bean
    public NotifierPlugin teamsNotifierPlugin(
            @Value("${TEAMS_WEBHOOK_URL:}") String webhookUrl) {
        return new TeamsNotifierPlugin(webhookUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "nucleus.notifiers.slack.enabled", havingValue = "true")
    public NotifierPlugin slackNotifierPlugin(
            @Value("${SLACK_WEBHOOK_URL:}") String webhookUrl) {
        return new SlackNotifierPlugin(webhookUrl);
    }

    @Bean
    @ConditionalOnProperty(name = "nucleus.notifiers.desktop.enabled", havingValue = "true")
    public NotifierPlugin desktopNotifierPlugin() {
        return new DesktopNotifierPlugin();
    }

    @Bean
    public RestTemplate restTemplate() {
        return new RestTemplate();
    }
}
