package com.visa.nucleus.plugins.runtime;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.plugins.workspace.ProcessRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TmuxRuntimePluginTest {

    @Mock
    private ProcessRunner processRunner;

    private TmuxRuntimePlugin plugin;

    @BeforeEach
    void setUp() {
        plugin = new TmuxRuntimePlugin(processRunner);
    }

    // -----------------------------------------------------------------------
    // start()
    // -----------------------------------------------------------------------

    @Test
    void start_createsNewTmuxSessionInWorktreeDirectory() throws Exception {
        AgentSession session = new AgentSession("sess-1");
        session.setWorktreePath("/tmp/worktrees/sess-1");

        plugin.start(session);

        verify(processRunner).run(
                eq(List.of("tmux", "new-session", "-d", "-s", "nucleus-sess-1", "-c", "/tmp/worktrees/sess-1")),
                eq("/tmp/worktrees/sess-1")
        );
    }

    @Test
    void start_setsSessionIdEnvVar() throws Exception {
        AgentSession session = new AgentSession("sess-1");
        session.setWorktreePath("/tmp/worktrees/sess-1");

        plugin.start(session);

        verify(processRunner).run(
                eq(List.of("tmux", "setenv", "-t", "nucleus-sess-1", "SESSION_ID", "sess-1")),
                eq("/tmp/worktrees/sess-1")
        );
    }

    @Test
    void start_storesTmuxSessionNameAsContainerId() throws Exception {
        AgentSession session = new AgentSession("sess-2");
        session.setWorktreePath("/tmp/worktrees/sess-2");

        plugin.start(session);

        assertEquals("nucleus-sess-2", session.getContainerId());
    }

    // -----------------------------------------------------------------------
    // stop()
    // -----------------------------------------------------------------------

    @Test
    void stop_killsTmuxSession() throws Exception {
        plugin.stop("sess-1");

        verify(processRunner).run(
                eq(List.of("tmux", "kill-session", "-t", "nucleus-sess-1")),
                eq("/tmp")
        );
    }

    @Test
    void stop_silentlyIgnoresWhenSessionNotFound() throws Exception {
        when(processRunner.run(any(), anyString()))
                .thenThrow(new RuntimeException("no server running on /tmp/tmux-..."));

        assertDoesNotThrow(() -> plugin.stop("nonexistent-sess"));
    }

    // -----------------------------------------------------------------------
    // sendInstruction()
    // -----------------------------------------------------------------------

    @Test
    void sendInstruction_sendsKeysToTmuxSession() throws Exception {
        plugin.sendInstruction("sess-1", "run the tests");

        verify(processRunner).run(
                eq(List.of("tmux", "send-keys", "-t", "nucleus-sess-1", "run the tests", "Enter")),
                eq("/tmp")
        );
    }

    @Test
    void sendInstruction_appendsToInstructionFileWhenWorktreeKnown() throws Exception {
        AgentSession session = new AgentSession("sess-1");
        session.setWorktreePath("/tmp/worktrees/sess-1");
        plugin.start(session);
        reset(processRunner);

        plugin.sendInstruction("sess-1", "run the tests");

        verify(processRunner).run(
                eq(List.of("sh", "-c",
                        "echo 'run the tests' >> /tmp/worktrees/sess-1/.nucleus-instructions.txt")),
                eq("/tmp/worktrees/sess-1")
        );
    }

    @Test
    void sendInstruction_escapesSingleQuotesInInstruction() throws Exception {
        AgentSession session = new AgentSession("sess-1");
        session.setWorktreePath("/tmp/worktrees/sess-1");
        plugin.start(session);
        reset(processRunner);

        plugin.sendInstruction("sess-1", "it's a test");

        verify(processRunner).run(
                eq(List.of("sh", "-c",
                        "echo 'it'\\''s a test' >> /tmp/worktrees/sess-1/.nucleus-instructions.txt")),
                eq("/tmp/worktrees/sess-1")
        );
    }

    @Test
    void sendInstruction_skipsFileAppendWhenWorktreeUnknown() throws Exception {
        // sendInstruction called without prior start — worktree unknown
        plugin.sendInstruction("sess-orphan", "hello");

        // only the send-keys command should be issued
        verify(processRunner, times(1)).run(any(), anyString());
        verify(processRunner).run(
                eq(List.of("tmux", "send-keys", "-t", "nucleus-sess-orphan", "hello", "Enter")),
                eq("/tmp")
        );
    }

    // -----------------------------------------------------------------------
    // getLogs()
    // -----------------------------------------------------------------------

    @Test
    void getLogs_capturesPaneOutputWithScrollback() throws Exception {
        when(processRunner.run(any(), anyString())).thenReturn("pane output here");

        String logs = plugin.getLogs("sess-1");

        verify(processRunner).run(
                eq(List.of("tmux", "capture-pane", "-t", "nucleus-sess-1", "-p", "-S", "-200")),
                eq("/tmp")
        );
        assertEquals("pane output here", logs);
    }

    @Test
    void getLogs_returnsEmptyStringWhenSessionNotFound() throws Exception {
        when(processRunner.run(any(), anyString()))
                .thenThrow(new RuntimeException("can't find session: nucleus-unknown"));

        assertEquals("", plugin.getLogs("unknown-sess"));
    }

    // -----------------------------------------------------------------------
    // isRunning()
    // -----------------------------------------------------------------------

    @Test
    void isRunning_returnsTrueWhenSessionExists() throws Exception {
        when(processRunner.run(any(), anyString())).thenReturn("");

        assertTrue(plugin.isRunning("sess-1"));
        verify(processRunner).run(
                eq(List.of("tmux", "has-session", "-t", "nucleus-sess-1")),
                eq("/tmp")
        );
    }

    @Test
    void isRunning_returnsFalseWhenSessionNotFound() throws Exception {
        when(processRunner.run(any(), anyString()))
                .thenThrow(new RuntimeException("Command failed with exit code 1"));

        assertFalse(plugin.isRunning("missing-sess"));
    }

    @Test
    void isRunning_returnsFalseOnUnexpectedException() throws Exception {
        when(processRunner.run(any(), anyString()))
                .thenThrow(new RuntimeException("unexpected error"));

        assertFalse(plugin.isRunning("sess-1"));
    }
}
