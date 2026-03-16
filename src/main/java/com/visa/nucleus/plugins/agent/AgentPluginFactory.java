package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.plugin.AgentPlugin;
import org.springframework.stereotype.Component;

/**
 * Factory that returns the appropriate AgentPlugin implementation based on the requested agent type.
 *
 * Both plugin instances are held as singletons so that their per-session conversation
 * state persists across calls.
 */
@Component
public class AgentPluginFactory {

    private final ClaudeAgentPlugin claudePlugin = new ClaudeAgentPlugin();
    private final OpenAIAgentPlugin openAiPlugin = new OpenAIAgentPlugin();

    /**
     * Returns an AgentPlugin for the given agent type.
     *
     * @param agentType "claude" for ClaudeAgentPlugin, "openai" for OpenAIAgentPlugin
     * @return the matching AgentPlugin
     * @throws IllegalArgumentException if the agent type is not supported
     */
    public AgentPlugin create(String agentType) {
        if (agentType == null) {
            throw new IllegalArgumentException("agentType must not be null");
        }
        return switch (agentType.toLowerCase()) {
            case "claude" -> claudePlugin;
            case "openai" -> openAiPlugin;
            default -> throw new IllegalArgumentException("Unsupported agent type: " + agentType);
        };
    }
}
