package com.visa.nucleus.config;

import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.plugins.notifier.DesktopNotifierPlugin;
import com.visa.nucleus.plugins.notifier.SlackNotifierPlugin;
import com.visa.nucleus.plugins.notifier.TeamsNotifierPlugin;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Spring configuration that wires plugin beans from {@link NucleusProperties}.
 */
@Configuration
@EnableConfigurationProperties(NucleusProperties.class)
public class NucleusConfig {

    @Bean
    public List<NotifierPlugin> notifierPlugins(NucleusProperties props) {
        return props.getDefaults().getNotifiers().stream()
            .map(type -> switch (type) {
                case "teams"   -> (NotifierPlugin) new TeamsNotifierPlugin();
                case "slack"   -> new SlackNotifierPlugin();
                case "desktop" -> new DesktopNotifierPlugin();
                default -> throw new IllegalArgumentException("Unknown notifier: " + type);
            })
            .toList();
    }
}
