package com.visa.nucleus.core.plugin;

public interface NotifierPlugin {
    void notify(String sessionId, String message, NotificationLevel level) throws Exception;
}
