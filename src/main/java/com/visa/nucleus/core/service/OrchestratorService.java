package com.visa.nucleus.core.service;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.config.ProjectConfig;
import com.visa.nucleus.config.ReactionRule;
import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.plugin.AgentPlugin;
import com.visa.nucleus.core.plugin.NotificationLevel;
import com.visa.nucleus.core.plugin.NotifierPlugin;
import com.visa.nucleus.core.plugin.RuntimePlugin;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import com.visa.nucleus.core.plugin.WorkspacePlugin;
import com.visa.nucleus.plugins.agent.AgentPluginFactory;
import com.visa.nucleus.plugins.runtime.RuntimePluginFactory;
import com.visa.nucleus.plugins.tracker.TrackerPluginFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;
import java.util.Map;

/**
 * OrchestratorService is the main brain that coordinates all six plugins.
 *
 * <p>Multi-project tracker support: the tracker type is resolved per-project via
 * {@link TrackerPluginFactory} using the {@code tracker} field from each project's
 * config in {@code agent-orchestrator.yaml}. Falls back to {@code defaults.tracker}
 * (default: "jira") when a project does not specify one.
 */
@Service
public class OrchestratorService {

    private static final String DEFAULT_AGENT_TYPE = "claude";
    private static final String DEFAULT_RUNTIME = "docker";
    private static final String DEFAULT_TRACKER = "jira";
    private static final int FALLBACK_CI_RETRIES = 3;

    private final SessionManager sessionManager;
    private final ProjectService projectService;
    private final TrackerPluginFactory trackerPluginFactory;
    private final WorkspacePlugin workspacePlugin;
    private final AgentPluginFactory agentPluginFactory;
    private final RuntimePluginFactory runtimePluginFactory;
    private final List<NotifierPlugin> notifierPlugins;
    private final NucleusProperties nucleusProperties;
    private final String repoPath;

    public OrchestratorService(
            SessionManager sessionManager,
            ProjectService projectService,
            TrackerPluginFactory trackerPluginFactory,
            WorkspacePlugin workspacePlugin,
            AgentPluginFactory agentPluginFactory,
            RuntimePluginFactory runtimePluginFactory,
            List<NotifierPlugin> notifierPlugins,
            NucleusProperties nucleusProperties,
            @Value("${NUCLEUS_REPO_PATH:/tmp}") String repoPath) {
        this.sessionManager = sessionManager;
        this.projectService = projectService;
        this.trackerPluginFactory = trackerPluginFactory;
        this.workspacePlugin = workspacePlugin;
        this.agentPluginFactory = agentPluginFactory;
        this.runtimePluginFactory = runtimePluginFactory;
        this.notifierPlugins = notifierPlugins;
        this.nucleusProperties = nucleusProperties;
        this.repoPath = repoPath;
    }

    public ProjectConfig getProjectConfig(String projectName) {
        return nucleusProperties.getProjects().get(projectName);
    }

    private int maxCiRetries(String projectName) {
        ReactionRule rule = nucleusProperties.getReactions().get("ci-failed");
        if (rule != null && rule.getRetries() > 0) {
            return rule.getRetries();
        }
        return FALLBACK_CI_RETRIES;
    }

    /**
     * Resolves the tracker type for the given project.
     * Priority: project-level config → defaults.tracker → "jira"
     */
    private String resolveTrackerType(String projectName) {
        Map<String, ProjectConfig> projects = nucleusProperties.getProjects();
        if (projects != null) {
            ProjectConfig config = projects.get(projectName);
            if (config != null && config.getTracker() != null) {
                return config.getTracker();
            }
        }
        NucleusProperties.Defaults defaults = nucleusProperties.getDefaults();
        if (defaults != null && defaults.getTracker() != null) {
            return defaults.getTracker();
        }
        return DEFAULT_TRACKER;
    }

    public AgentSession spawn(String projectName, String ticketId) throws Exception {
        Project project = projectService.getProject(projectName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectName));

        String projectRepoPath = project.getPath() != null ? project.getPath() : repoPath;

        AgentSession session = new AgentSession(projectName, ticketId);
        session.setAgentType(resolveAgentType(session));
        sessionManager.save(session);

        TrackerPlugin trackerPlugin = trackerPluginFactory.create(
                resolveTrackerType(projectName), nucleusProperties);
        String issueContext = trackerPlugin.getIssueContext(ticketId);

        String branchName = workspacePlugin.generateBranchName(ticketId, projectName);
        String worktreePath = workspacePlugin.createWorktree(projectRepoPath, branchName);
        session.setBranchName(branchName);
        session.setWorktreePath(worktreePath);

        session.setStatus(AgentSession.Status.RUNNING);

        RuntimePlugin runtimePlugin = runtimePluginFactory.create(resolveRuntime(session));
        runtimePlugin.start(session);

        AgentPlugin agentPlugin = agentPluginFactory.create(resolveAgentType(session));
        agentPlugin.initialize(session, issueContext);

        sessionManager.save(session);
        return session;
    }

    public void terminate(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        RuntimePlugin runtimePlugin = runtimePluginFactory.create(resolveRuntime(session));
        runtimePlugin.stop(sessionId);

        if (session.getWorktreePath() != null) {
            workspacePlugin.deleteWorktree(session.getWorktreePath());
        }

        session.setStatus(AgentSession.Status.FAILED);
        sessionManager.save(session);
    }

    public AgentSession restore(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        if (session.getStatus() != AgentSession.Status.FAILED
                && session.getStatus() != AgentSession.Status.RUNNING) {
            throw new IllegalStateException(
                    "Session " + sessionId + " cannot be restored from status: " + session.getStatus());
        }

        if (session.getWorktreePath() == null || !new File(session.getWorktreePath()).exists()) {
            String projectRepoPath = projectService.getProject(session.getProjectName())
                    .map(Project::getPath)
                    .orElse(repoPath);
            String path = workspacePlugin.restoreWorktree(projectRepoPath, session.getBranchName());
            session.setWorktreePath(path);
        }

        RuntimePlugin runtimePlugin = runtimePluginFactory.create(resolveRuntime(session));
        runtimePlugin.start(session);

        TrackerPlugin trackerPlugin = trackerPluginFactory.create(
                resolveTrackerType(session.getProjectName()), nucleusProperties);
        String issueContext = trackerPlugin.getIssueContext(session.getTicketId());

        AgentPlugin agentPlugin = agentPluginFactory.create(resolveAgentType(session));
        agentPlugin.initialize(session, issueContext);

        session.setStatus(AgentSession.Status.RUNNING);
        sessionManager.save(session);

        return session;
    }

    public void handleCiFailure(String sessionId, String ciLogs) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        AgentPlugin agentPlugin = agentPluginFactory.create(resolveAgentType(session));
        agentPlugin.sendMessage(sessionId, "CI failed. Logs: " + ciLogs);

        session.incrementCiRetryCount();

        if (session.getCiRetryCount() > maxCiRetries(session.getProjectName())) {
            notifyAll(sessionId,
                    "Session " + sessionId + " has failed CI " + session.getCiRetryCount() + " times and needs attention.",
                    NotificationLevel.NEEDS_ATTENTION);
        }

        sessionManager.save(session);
    }

    public void handleReviewComment(String sessionId, String comment) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));

        AgentPlugin agentPlugin = agentPluginFactory.create(resolveAgentType(session));
        agentPlugin.sendMessage(sessionId, "Reviewer says: " + comment);

        session.setStatus(AgentSession.Status.RUNNING);
        sessionManager.save(session);
    }

    /**
     * Returns the AgentPlugin for the given session's project agent type.
     * Used by SessionController to route manual messages to the correct agent.
     */
    public AgentPlugin agentPluginForSession(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        return agentPluginFactory.create(resolveAgentType(session));
    }

    /**
     * Returns the RuntimePlugin for the given session's project runtime.
     * Used by SessionController to fetch runtime logs for the correct backend.
     */
    public RuntimePlugin runtimePluginForSession(String sessionId) throws Exception {
        AgentSession session = sessionManager.getSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("Session not found: " + sessionId));
        return runtimePluginFactory.create(resolveRuntime(session));
    }

    private void notifyAll(String sessionId, String message, NotificationLevel level) throws Exception {
        for (NotifierPlugin notifier : notifierPlugins) {
            notifier.notify(sessionId, message, level);
        }
    }

    private String resolveAgentType(AgentSession session) {
        Project project = projectService.getProject(session.getProjectName()).orElse(null);
        if (project != null && project.getAgentType() != null) return project.getAgentType();
        NucleusProperties.Defaults defaults = nucleusProperties.getDefaults();
        String def = defaults != null ? defaults.getAgent() : null;
        return def != null ? def : DEFAULT_AGENT_TYPE;
    }

    private String resolveRuntime(AgentSession session) {
        Project project = projectService.getProject(session.getProjectName()).orElse(null);
        if (project != null && project.getRuntime() != null) return project.getRuntime();
        NucleusProperties.Defaults defaults = nucleusProperties.getDefaults();
        String def = defaults != null ? defaults.getRuntime() : null;
        return def != null ? def : DEFAULT_RUNTIME;
    }
}
