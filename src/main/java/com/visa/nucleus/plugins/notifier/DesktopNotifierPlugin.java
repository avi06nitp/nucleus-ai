package com.visa.nucleus.plugins.notifier;

import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.plugins.workspace.ProcessRunner;

import java.util.List;
import java.util.logging.Logger;

/**
 * NotifierPlugin implementation that sends desktop notifications.
 *
 * Supports:
 *   macOS — via osascript
 *   Linux — via notify-send
 *
 * Logs a warning and no-ops if the current OS is not supported.
 */
public class DesktopNotifierPlugin implements NotifierPlugin {

    private static final Logger log = Logger.getLogger(DesktopNotifierPlugin.class.getName());

    private final ProcessRunner processRunner;
    private final String osName;

    public DesktopNotifierPlugin() {
        this(new ProcessRunner(), System.getProperty("os.name"));
    }

    // Package-private constructor for testing
    DesktopNotifierPlugin(ProcessRunner processRunner, String osName) {
        this.processRunner = processRunner;
        this.osName = osName;
    }

    @Override
    public void notify(String sessionId, String message, NotificationLevel level) throws Exception {
        String os = osName != null ? osName.toLowerCase() : "";

        if (os.contains("mac") || os.contains("darwin")) {
            sendMacOsNotification(sessionId, message);
        } else if (os.contains("linux")) {
            sendLinuxNotification(sessionId, message);
        } else {
            log.warning("Desktop notifications are not supported on OS: " + osName + ". Skipping.");
        }
    }

    private void sendMacOsNotification(String sessionId, String message) throws Exception {
        String safeMessage = sanitizeForShell(message);
        String safeSessionId = sanitizeForShell(sessionId);
        String script = "display notification \"" + safeMessage
            + "\" with title \"Nucleus AI\" subtitle \"Session " + safeSessionId + "\"";
        processRunner.run(List.of("osascript", "-e", script), System.getProperty("java.io.tmpdir"));
    }

    private void sendLinuxNotification(String sessionId, String message) throws Exception {
        String safeMessage = sanitizeForShell(message);
        processRunner.run(
            List.of("notify-send", "Nucleus AI", safeMessage),
            System.getProperty("java.io.tmpdir")
        );
    }

    /**
     * Strips characters that could escape the AppleScript/shell string context.
     * Only double-quotes and backslashes need to be removed since the strings are
     * embedded inside double-quoted AppleScript/shell literals.
     */
    private String sanitizeForShell(String value) {
        if (value == null) return "";
        return value.replace("\\", "").replace("\"", "");
    }
}
