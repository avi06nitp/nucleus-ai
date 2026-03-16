package com.visa.nucleus;

import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.plugins.runtime.DockerRuntimePlugin;
import com.visa.nucleus.plugins.runtime.TmuxRuntimePlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class NucleusConfig {

    @Bean
    public RuntimePlugin runtimePlugin(NucleusProperties props) {
        return switch (props.getDefaults().getRuntime()) {
            case "tmux" -> new TmuxRuntimePlugin();
            case "docker" -> new DockerRuntimePlugin();
            default -> throw new IllegalArgumentException(
                    "Unknown runtime: " + props.getDefaults().getRuntime());
        };
    }
}
