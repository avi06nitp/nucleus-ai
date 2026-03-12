package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ProjectConfig;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;

/**
 * OrchestratorService is the main brain that coordinates all six plugins:
 * TrackerPlugin, WorkspacePlugin, RuntimePlugin, AgentPlugin, NotifierPlugin,
 * and ScmPlugin. It manages the full lifecycle of an agent session.
 *
 * <p>Plugin selection and reaction behaviour is driven by {@link NucleusProperties}
 * loaded from {@code agent-orchestrator.yaml}.
 */
@Service
public class OrchestratorService {

    private final SessionManager sessionManager;
    private final TrackerPlugin trackerPlugin;
    private final WorkspacePlugin workspacePlugin;
    private final RuntimePlugin runtimePlugin;
    private final AgentPlugin agentPlugin;
    private final NotifierPlugin notifierPlugin;
    private final NucleusProperties nucleusProperties;
    private final String repoPath;

    public OrchestratorService(
            SessionManager sessionManager,
            TrackerPlugin trackerPlugin,
            WorkspacePlugin workspacePlugin,
            RuntimePlugin runtimePlugin,
            AgentPlugin agentPlugin,
            NotifierPlugin notifierPlugin,
            NucleusProperties nucleusProperties,
            @Value("${NUCLEUS_REPO_PATH:/tmp}") String repoPath) {
        this.sessionManager = sessionManager;
        this.trackerPlugin = trackerPlugin;
        this.workspacePlugin = workspacePlugin;
        this.runtimePlugin = runtimePlugin;
        this.agentPlugin = agentPlugin;
        this.notifierPlugin = notifierPlugin;
        this.nucleusProperties = nucleusProperties;
        this.repoPath = repoPath;
    }

    /**
     * Returns the effective {@link ProjectConfig} for the given project name,
     * or {@code null} if the project is not explicitly configured.
     */
    public ProjectConfig getProjectConfig(String projectName) {
        return nucleusProperties.getProjects().get(projectName);
    }

    /**
     * Returns the effective max CI retries for a given project, falling back to
     * the reaction rule configured under {@code ci-failed}, then to 3.
     */
    private int maxCiRetries(String projectName) {
        ReactionRule rule = nucleusProperties.getReactions().get("ci-failed");
        if (rule != null && rule.getRetries() > 0) {
            return rule.getRetries();
        }
        return 3;
    }

    /**
     * Spawns a new agent session for the given project and ticket.
     *
     * <ol>
     *   <li>Creates an AgentSession with PENDING status and saves it to DB.</li>
     *   <li>Fetches issue context from the tracker (Jira/GitHub).</li>
     *   <li>Creates a git worktree for isolated development.</li>
     *   <li>Updates session status to RUNNING.</li>
     *   <li>Starts the Docker runtime container for this session.</li>
     *   <li>Initializes the AI agent with the issue context.</li>
     *   <li>Saves the updated session.</li>
     * </ol>
     *
     * @return the created and running AgentSession
     */
    public AgentSession spawn(String projectName, String ticketId) throws Exception {
        // 1. Create session with PENDING status
        AgentSession session = new AgentSession(projectName, ticketId);
        sessionManager.save(session);

        // 2. Fetch issue context from tracker
        String issueContext = trackerPlugin.getIssueContext(ticketId);

        // 3. Create git worktree
        String branchName = workspacePlugin.generateBranchName(ticketId, projectName);
        String worktreePath = workspacePlugin.createWorktree(repoPath, branchName);
        session.setBranchName(branchName);
        session.setWorktreePath(worktreePath);

        // 4. Update status to RUNNING
        session.setStatus(AgentSession.Status.RUNNING);

        // 5. Start Docker container
        runtimePlugin.start(session);

        // 6. Initialize AI agent with issue context
        agentPlugin.initialize(session, issueContext);

        // 7. Save updated session
        sessionManager.save(session);

        return session;
    }

    /**
     * Terminates an existing agent session.
     *
     * <ol>
     *   <li>Stops the Docker runtime container.</li>
     *   <li>Removes the git worktree.</li>
     *   <li>Updates session status to FAILED and saves.</li>
     * </ol>
     */
    public void terminate(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        // 1. Stop runtime container
        runtimePlugin.stop(sessionId);

        // 2. Delete worktree if present
        if (session.getWorktreePath() != null) {
            workspacePlugin.deleteWorktree(session.getWorktreePath());
        }

        // 3. Mark session as FAILED and save
        session.setStatus(AgentSession.Status.FAILED);
        sessionManager.save(session);
    }

    /**
     * Restores a FAILED (or RUNNING) agent session without losing its git branch.
     *
     * <ol>
     *   <li>Fetches the session — must exist and be in FAILED or RUNNING state.</li>
     *   <li>Recreates the git worktree from the existing branch if the directory is gone.</li>
     *   <li>Restarts the Docker runtime container.</li>
     *   <li>Re-fetches issue context from the tracker and re-initializes the agent.</li>
     *   <li>Sets status back to RUNNING and saves.</li>
     * </ol>
     *
     * @return the restored AgentSession in RUNNING state
     */
    public AgentSession restore(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != AgentSession.Status.FAILED
                && session.getStatus() != AgentSession.Status.RUNNING) {
            throw new IllegalStateException(
                    "Session " + sessionId + " cannot be restored from status: " + session.getStatus());
        }

        // Recreate worktree if missing
        if (session.getWorktreePath() == null || !new File(session.getWorktreePath()).exists()) {
            String path = workspacePlugin.restoreWorktree(repoPath, session.getBranchName());
            session.setWorktreePath(path);
        }

        // Restart runtime
        runtimePlugin.start(session);

        // Re-fetch issue context and re-initialize agent
        String issueContext = trackerPlugin.getIssueContext(session.getTicketId());
        agentPlugin.initialize(session, issueContext);

        session.setStatus(AgentSession.Status.RUNNING);
        sessionManager.save(session);

        return session;
    }

    /**
     * Handles a CI failure by forwarding logs to the agent and escalating if retry
     * count exceeds the threshold.
     *
     * <ol>
     *   <li>Fetches the session from DB.</li>
     *   <li>Forwards the CI failure logs to the agent.</li>
     *   <li>Increments the ciRetryCount.</li>
     *   <li>If retryCount exceeds the configured {@code reactions.ci-failed.retries} limit, notifies via notifierPlugin with
     *       NEEDS_ATTENTION level.</li>
     *   <li>Saves the updated session.</li>
     * </ol>
     */
    public void handleCiFailure(String sessionId, String ciLogs) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        // 2. Forward CI failure logs to agent
        agentPlugin.sendMessage(sessionId, "CI failed. Logs: " + ciLogs);

        // 3. Increment retry count
        session.incrementCiRetryCount();

        // 4. Escalate if too many retries (limit comes from reactions.ci-failed.retries)
        if (session.getCiRetryCount() > maxCiRetries(session.getProjectName())) {
            notifierPlugin.notify(sessionId,
                    "Session " + sessionId + " has failed CI " + session.getCiRetryCount() + " times and needs attention.",
                    NotificationLevel.NEEDS_ATTENTION);
        }

        // 5. Save session
        sessionManager.save(session);
    }

    /**
     * Forwards a reviewer comment to the agent and updates the session status.
     *
     * <ol>
     *   <li>Fetches the session from DB.</li>
     *   <li>Sends the reviewer comment to the agent.</li>
     *   <li>Updates the session status to RUNNING (agent is now addressing feedback).</li>
     *   <li>Saves the session.</li>
     * </ol>
     */
    public void handleReviewComment(String sessionId, String comment) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        // 2. Forward reviewer comment to agent
        agentPlugin.sendMessage(sessionId, "Reviewer says: " + comment);

        // 3. Update status to RUNNING (agent is addressing feedback)
        session.setStatus(AgentSession.Status.RUNNING);

        // 4. Save session
        sessionManager.save(session);
    }
}
