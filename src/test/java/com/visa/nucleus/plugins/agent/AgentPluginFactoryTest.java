package com.visa.nucleus.plugins.agent;

import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.plugins.runtime.DockerRuntimePlugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class AgentPluginFactoryTest {

    private final AgentPluginFactory factory = new AgentPluginFactory(mock(DockerRuntimePlugin.class));

    @Test
    void create_returnsClaude() {
        AgentPlugin plugin = factory.create("claude");
        assertInstanceOf(ClaudeAgentPlugin.class, plugin);
        assertEquals("claude", plugin.getAgentType());
    }

    @Test
    void create_returnsOpenAI() {
        AgentPlugin plugin = factory.create("openai");
        assertInstanceOf(OpenAIAgentPlugin.class, plugin);
        assertEquals("openai", plugin.getAgentType());
    }

    @Test
    void create_isCaseInsensitive() {
        assertInstanceOf(ClaudeAgentPlugin.class, factory.create("Claude"));
        assertInstanceOf(OpenAIAgentPlugin.class, factory.create("OpenAI"));
    }

    @Test
    void create_throwsForUnknownType() {
        assertThrows(IllegalArgumentException.class, () -> factory.create("gemini"));
    }

    @Test
    void create_throwsForNull() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(null));
    }
}
