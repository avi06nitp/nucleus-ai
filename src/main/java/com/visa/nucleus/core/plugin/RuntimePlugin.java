package com.visa.nucleus.core.plugin;

import com.visa.nucleus.core.AgentSession;

public interface RuntimePlugin {
    void start(AgentSession session) throws Exception;
    void stop(String sessionId) throws Exception;
    void sendInstruction(String sessionId, String instruction) throws Exception;
    String getLogs(String sessionId) throws Exception;
    boolean isRunning(String sessionId);
}
