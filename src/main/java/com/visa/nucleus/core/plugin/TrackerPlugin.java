package com.visa.nucleus.core.plugin;

public interface TrackerPlugin {
    String getIssueContext(String ticketId) throws Exception;
    void addComment(String ticketId, String comment) throws Exception;
    void transitionStatus(String ticketId, String toStatus) throws Exception;
}
