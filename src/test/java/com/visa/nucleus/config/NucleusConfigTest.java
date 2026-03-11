package com.visa.nucleus.config;

import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.plugins.notifier.DesktopNotifierPlugin;
import com.visa.nucleus.plugins.notifier.SlackNotifierPlugin;
import com.visa.nucleus.plugins.notifier.TeamsNotifierPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class NucleusConfigTest {

    private final NucleusConfig config = new NucleusConfig();

    private NucleusProperties propsWithNotifiers(String... types) {
        NucleusProperties props = new NucleusProperties();
        props.getDefaults().setNotifiers(List.of(types));
        return props;
    }

    @Test
    void notifierPlugins_returnsSlack() {
        List<NotifierPlugin> plugins = config.notifierPlugins(propsWithNotifiers("slack"));
        assertEquals(1, plugins.size());
        assertInstanceOf(SlackNotifierPlugin.class, plugins.get(0));
    }

    @Test
    void notifierPlugins_returnsDesktop() {
        List<NotifierPlugin> plugins = config.notifierPlugins(propsWithNotifiers("desktop"));
        assertEquals(1, plugins.size());
        assertInstanceOf(DesktopNotifierPlugin.class, plugins.get(0));
    }

    @Test
    void notifierPlugins_returnsTeams() {
        List<NotifierPlugin> plugins = config.notifierPlugins(propsWithNotifiers("teams"));
        assertEquals(1, plugins.size());
        assertInstanceOf(TeamsNotifierPlugin.class, plugins.get(0));
    }

    @Test
    void notifierPlugins_returnsMultiple() {
        List<NotifierPlugin> plugins = config.notifierPlugins(propsWithNotifiers("slack", "desktop"));
        assertEquals(2, plugins.size());
        assertInstanceOf(SlackNotifierPlugin.class, plugins.get(0));
        assertInstanceOf(DesktopNotifierPlugin.class, plugins.get(1));
    }

    @Test
    void notifierPlugins_throwsForUnknownType() {
        assertThrows(IllegalArgumentException.class,
            () -> config.notifierPlugins(propsWithNotifiers("unknown")));
    }

    @Test
    void nucleusProperties_defaultsToTeams() {
        NucleusProperties props = new NucleusProperties();
        assertEquals(List.of("teams"), props.getDefaults().getNotifiers());
    }
}
