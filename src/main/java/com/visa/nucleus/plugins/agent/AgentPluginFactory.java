package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.plugin.AgentPlugin;

/**
 * Factory that returns the appropriate AgentPlugin implementation based on the requested agent type.
 */
public class AgentPluginFactory {

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
        switch (agentType.toLowerCase()) {
            case "claude": return new ClaudeAgentPlugin();
            case "openai": return new OpenAIAgentPlugin();
            default:
                throw new IllegalArgumentException("Unsupported agent type: " + agentType);
        }
    }
}
