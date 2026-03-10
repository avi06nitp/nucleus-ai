package com.visa.nucleus.core.plugin;

import java.util.List;

public interface ScmPlugin {
    String createPullRequest(String branch, String title, String body) throws Exception;
    List<String> getReviewComments(String prUrl) throws Exception;
    String getCiLogs(String prUrl) throws Exception;
    void replyToComment(String commentId, String reply) throws Exception;
}
