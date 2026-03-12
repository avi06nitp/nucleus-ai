package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.plugins.workspace.ProcessRunner;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DesktopNotifierPluginTest {

    @Test
    void notify_sends_osascript_on_macos() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, "Mac OS X");

        plugin.notify("session-1", "Build done", NotificationLevel.INFO);

        verify(processRunner).run(
            argThat(cmd -> cmd.get(0).equals("osascript") && cmd.get(1).equals("-e")),
            anyString()
        );
    }

    @Test
    void notify_sends_osascript_on_darwin() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, "Darwin");

        plugin.notify("session-1", "Build done", NotificationLevel.INFO);

        verify(processRunner).run(
            argThat(cmd -> cmd.get(0).equals("osascript")),
            anyString()
        );
    }

    @Test
    void notify_sends_notify_send_on_linux() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, "Linux");

        plugin.notify("session-1", "Build done", NotificationLevel.INFO);

        verify(processRunner).run(
            argThat(cmd -> cmd.get(0).equals("notify-send")),
            anyString()
        );
    }

    @Test
    void notify_noops_on_unsupported_os() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, "Windows 10");

        assertDoesNotThrow(() -> plugin.notify("session-1", "Build done", NotificationLevel.INFO));

        verify(processRunner, never()).run(any(), any());
    }

    @Test
    void notify_noops_when_os_name_is_null() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, null);

        assertDoesNotThrow(() -> plugin.notify("session-1", "msg", NotificationLevel.INFO));

        verify(processRunner, never()).run(any(), any());
    }

    @Test
    void notify_sanitizes_message_with_special_characters() throws Exception {
        ProcessRunner processRunner = mock(ProcessRunner.class);
        DesktopNotifierPlugin plugin = new DesktopNotifierPlugin(processRunner, "Mac OS X");

        // Should not throw even with embedded quotes and backslashes
        assertDoesNotThrow(() ->
            plugin.notify("session-1", "Error: \"quotes\" and \\backslash", NotificationLevel.NEEDS_ATTENTION));

        verify(processRunner).run(any(List.class), anyString());
    }
}
