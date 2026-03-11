package com.visa.nucleus.core.plugin;

public interface WorkspacePlugin {
    String createWorktree(String repoPath, String branchName) throws Exception;
    String restoreWorktree(String repoPath, String branchName) throws Exception;
    void deleteWorktree(String worktreePath) throws Exception;
    String generateBranchName(String ticketId, String ticketTitle);
}
