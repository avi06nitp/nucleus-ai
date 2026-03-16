package com.visa.nucleus.plugins.runtime;

import com.visa.nucleus.core.plugin.RuntimePlugin;
import org.springframework.stereotype.Component;

/**
 * Factory that returns the appropriate RuntimePlugin implementation based on the
 * configured runtime type ("docker" or "tmux").
 *
 * Both plugin instances are held as singletons so that their per-session state
 * (container maps, process maps) persists across calls.
 */
@Component
public class RuntimePluginFactory {

    private final DockerRuntimePlugin dockerPlugin;
    private final TmuxRuntimePlugin tmuxPlugin;

    public RuntimePluginFactory(DockerRuntimePlugin dockerPlugin, TmuxRuntimePlugin tmuxPlugin) {
        this.dockerPlugin = dockerPlugin;
        this.tmuxPlugin = tmuxPlugin;
    }

    /**
     * Returns a RuntimePlugin for the given runtime type.
     *
     * @param runtime "docker" or "tmux" (defaults to "docker" when null/unrecognized)
     * @return the matching RuntimePlugin
     */
    public RuntimePlugin create(String runtime) {
        if ("tmux".equalsIgnoreCase(runtime)) {
            return tmuxPlugin;
        }
        // Default: docker
        return dockerPlugin;
    }
}
