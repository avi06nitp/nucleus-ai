package com.visa.nucleus.core.plugin;

import com.visa.nucleus.core.AgentSession;

public interface AgentPlugin {
    void initialize(AgentSession session, String issueContext) throws Exception;
    void sendMessage(String sessionId, String message) throws Exception;
    String getAgentType();
}
