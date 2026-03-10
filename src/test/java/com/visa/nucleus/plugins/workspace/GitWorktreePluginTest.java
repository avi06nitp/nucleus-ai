package com.visa.nucleus.plugins.workspace;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GitWorktreePluginTest {

    @Mock
    private ProcessRunner processRunner;

    private GitWorktreePlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new GitWorktreePlugin(processRunner);
    }

    @Test
    void createWorktree_invokesGitWorktreeAdd() throws Exception {
        String repoPath = "/some/repo";
        String branchName = "feat/JIRA-1-my-feature";
        String expectedPath = "/tmp/nucleus-worktrees/" + branchName;

        when(processRunner.run(any(), eq(repoPath))).thenReturn("");

        String result = plugin.createWorktree(repoPath, branchName);

        assertEquals(expectedPath, result);
        verify(processRunner).run(
            eq(List.of("git", "worktree", "add", "-b", branchName, expectedPath, "HEAD")),
            eq(repoPath)
        );
    }

    @Test
    void deleteWorktree_invokesGitWorktreeRemove() throws Exception {
        String worktreePath = "/tmp/nucleus-worktrees/feat/JIRA-1-my-feature";

        plugin.deleteWorktree(worktreePath);

        verify(processRunner).run(
            eq(List.of("git", "worktree", "remove", worktreePath, "--force")),
            eq(worktreePath)
        );
    }

    @Test
    void generateBranchName_slugifiesTitle() {
        assertEquals("feat/JIRA-421-add-lf-tag-governance",
            plugin.generateBranchName("JIRA-421", "Add LF Tag Governance"));
    }

    @Test
    void generateBranchName_removesSpecialChars() {
        assertEquals("feat/JIRA-1-fix-bug-with-api",
            plugin.generateBranchName("JIRA-1", "Fix bug with API!!!"));
    }

    @Test
    void generateBranchName_collapsesWhitespace() {
        assertEquals("feat/JIRA-2-update-readme",
            plugin.generateBranchName("JIRA-2", "  Update   README  "));
    }
}
