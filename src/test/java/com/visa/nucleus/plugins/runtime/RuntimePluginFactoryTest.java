package com.visa.nucleus.plugins.runtime;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class RuntimePluginFactoryTest {

    @Mock
    private DockerRuntimePlugin dockerPlugin;

    @Mock
    private TmuxRuntimePlugin tmuxPlugin;

    private RuntimePluginFactory factory;

    @BeforeEach
    void setUp() {
        factory = new RuntimePluginFactory(dockerPlugin, tmuxPlugin);
    }

    @Test
    void create_returnsTmuxPluginForTmuxRuntime() {
        assertThat(factory.create("tmux")).isSameAs(tmuxPlugin);
    }

    @Test
    void create_returnsTmuxPluginCaseInsensitive() {
        assertThat(factory.create("TMUX")).isSameAs(tmuxPlugin);
        assertThat(factory.create("Tmux")).isSameAs(tmuxPlugin);
    }

    @Test
    void create_returnsDockerPluginForDockerRuntime() {
        assertThat(factory.create("docker")).isSameAs(dockerPlugin);
    }

    @Test
    void create_returnsDockerPluginForUnrecognizedRuntime() {
        assertThat(factory.create("kubernetes")).isSameAs(dockerPlugin);
    }

    @Test
    void create_returnsDockerPluginForNullRuntime() {
        assertThat(factory.create(null)).isSameAs(dockerPlugin);
    }
}
