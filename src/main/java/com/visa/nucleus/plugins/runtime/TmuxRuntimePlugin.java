package com.visa.nucleus.plugins.runtime;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.plugins.workspace.ProcessRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RuntimePlugin implementation that manages agent sessions via tmux.
 *
 * Each agent session runs in a tmux session named nucleus-{sessionId}
 * with the session's worktree as the working directory.
 */
@Component
public class TmuxRuntimePlugin implements RuntimePlugin {

    static final String SESSION_PREFIX = "nucleus-";
    static final String INSTRUCTIONS_FILE = ".nucleus-instructions.txt";

    private final ProcessRunner processRunner;
    // Maps sessionId -> worktreePath so sendInstruction can append to the file
    private final ConcurrentHashMap<String, String> sessionWorktrees = new ConcurrentHashMap<>();

    /** Default constructor. */
    public TmuxRuntimePlugin() {
        this(new ProcessRunner());
    }

    /** Constructor for dependency injection (testing). */
    public TmuxRuntimePlugin(ProcessRunner processRunner) {
        this.processRunner = processRunner;
    }

    /**
     * Creates a new detached tmux session for the agent, sets environment variables,
     * and stores the tmux session name back on the AgentSession.
     */
    @Override
    public void start(AgentSession session) throws Exception {
        String tmuxSession = SESSION_PREFIX + session.getSessionId();
        String worktreePath = session.getWorktreePath();
        String anthropicKey = System.getenv("ANTHROPIC_API_KEY");

        processRunner.run(
                List.of("tmux", "new-session", "-d", "-s", tmuxSession, "-c", worktreePath),
                worktreePath
        );

        processRunner.run(
                List.of("tmux", "setenv", "-t", tmuxSession,
                        "ANTHROPIC_API_KEY", anthropicKey != null ? anthropicKey : ""),
                worktreePath
        );
        processRunner.run(
                List.of("tmux", "setenv", "-t", tmuxSession, "SESSION_ID", session.getSessionId()),
                worktreePath
        );

        session.setContainerId(tmuxSession);
        sessionWorktrees.put(session.getSessionId(), worktreePath);
    }

    /**
     * Kills the tmux session for the given sessionId.
     * Silently ignores if the session does not exist.
     */
    @Override
    public void stop(String sessionId) throws Exception {
        String tmuxSession = SESSION_PREFIX + sessionId;
        try {
            processRunner.run(List.of("tmux", "kill-session", "-t", tmuxSession), "/tmp");
        } catch (Exception ignored) {
            // silently ignore — session may not exist
        }
        sessionWorktrees.remove(sessionId);
    }

    /**
     * Appends the instruction to the agent's instruction file in the worktree,
     * then sends the instruction directly to the tmux session via send-keys.
     */
    @Override
    public void sendInstruction(String sessionId, String instruction) throws Exception {
        String tmuxSession = SESSION_PREFIX + sessionId;
        String worktreePath = sessionWorktrees.get(sessionId);

        if (worktreePath != null) {
            String escaped = instruction.replace("'", "'\\''");
            processRunner.run(
                    List.of("sh", "-c",
                            "echo '" + escaped + "' >> " + worktreePath + "/" + INSTRUCTIONS_FILE),
                    worktreePath
            );
        }

        processRunner.run(
                List.of("tmux", "send-keys", "-t", tmuxSession, instruction, "Enter"),
                "/tmp"
        );
    }

    /**
     * Captures the last 200 lines of the tmux pane and returns them as a string.
     */
    @Override
    public String getLogs(String sessionId) throws Exception {
        String tmuxSession = SESSION_PREFIX + sessionId;
        try {
            return processRunner.run(
                    List.of("tmux", "capture-pane", "-t", tmuxSession, "-p", "-S", "-200"),
                    "/tmp"
            );
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns true if a tmux session with the given sessionId is currently running.
     */
    @Override
    public boolean isRunning(String sessionId) {
        String tmuxSession = SESSION_PREFIX + sessionId;
        try {
            processRunner.run(List.of("tmux", "has-session", "-t", tmuxSession), "/tmp");
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
