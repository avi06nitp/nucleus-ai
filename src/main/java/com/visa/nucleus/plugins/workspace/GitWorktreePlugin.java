package com.visa.nucleus.plugins.workspace;

import com.visa.nucleus.core.plugin.WorkspacePlugin;

import java.io.File;
import java.util.List;

public class GitWorktreePlugin implements WorkspacePlugin {

    private static final String WORKTREE_BASE = "/tmp/nucleus-worktrees/";

    private final ProcessRunner processRunner;

    public GitWorktreePlugin() {
        this.processRunner = new ProcessRunner();
    }

    // Package-private for testing
    GitWorktreePlugin(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    @Override
    public String createWorktree(String repoPath, String branchName) throws Exception {
        File worktreeBase = new File(WORKTREE_BASE);
        if (!worktreeBase.exists()) {
            worktreeBase.mkdirs();
        }

        String worktreePath = WORKTREE_BASE + branchName;
        processRunner.run(
            List.of("git", "worktree", "add", "-b", branchName, worktreePath, "HEAD"),
            repoPath
        );
        return worktreePath;
    }

    @Override
    public String restoreWorktree(String repoPath, String branchName) throws Exception {
        File worktreeBase = new File(WORKTREE_BASE);
        if (!worktreeBase.exists()) {
            worktreeBase.mkdirs();
        }

        String worktreePath = WORKTREE_BASE + branchName;
        processRunner.run(
            List.of("git", "worktree", "add", worktreePath, branchName),
            repoPath
        );
        return worktreePath;
    }

    @Override
    public void deleteWorktree(String worktreePath) throws Exception {
        // Use the parent repo path — git worktree remove can be run from any git dir.
        // We derive the repo by walking up from the worktree path, but since the
        // worktree may not be a standard repo dir we just use the worktree path itself
        // as the working directory; git resolves the main repo via .git metadata.
        processRunner.run(
            List.of("git", "worktree", "remove", worktreePath, "--force"),
            worktreePath
        );
    }

    @Override
    public String generateBranchName(String ticketId, String ticketTitle) {
        String slug = ticketTitle
            .toLowerCase()
            .replaceAll("[^a-z0-9\\s-]", "")
            .trim()
            .replaceAll("\\s+", "-");
        return "feat/" + ticketId + "-" + slug;
    }
}
