package com.visa.nucleus.config;

import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import com.visa.nucleus.plugins.notifier.DesktopNotifierPlugin;
import com.visa.nucleus.plugins.notifier.SlackNotifierPlugin;
import com.visa.nucleus.plugins.notifier.TeamsNotifierPlugin;
import com.visa.nucleus.plugins.workspace.GitWorktreePlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

/**
 * Registers plugin implementations as Spring beans so they can be injected into
 * OrchestratorService and other components.
 *
 * <p>TrackerPlugin is no longer a singleton bean here. Instead,
 * {@link com.visa.nucleus.plugins.tracker.TrackerPluginFactory} (a {@code @Component})
 * is injected into OrchestratorService and resolves the correct TrackerPlugin
 * per-project at runtime based on {@code project.tracker} in agent-orchestrator.yaml.
 */
@Configuration
public class PluginConfig {

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
