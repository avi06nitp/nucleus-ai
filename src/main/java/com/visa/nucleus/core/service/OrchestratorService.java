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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/**
 * OrchestratorService is the main brain that coordinates all six plugins.
 * Multi-project support: project config is looked up by name from ProjectService
 * and the appropriate plugins are resolved via factories.
 */
@Service
public class OrchestratorService {

    private static final String DEFAULT_AGENT_TYPE = "claude";
    private static final String DEFAULT_RUNTIME = "docker";
    private static final int FALLBACK_CI_RETRIES = 3;

    private final SessionManager sessionManager;
    private final ProjectService projectService;
    private final TrackerPlugin trackerPlugin;
    private final WorkspacePlugin workspacePlugin;
    private final AgentPluginFactory agentPluginFactory;
    private final RuntimePluginFactory runtimePluginFactory;
    private final List<NotifierPlugin> notifierPlugins;
    private final NucleusProperties nucleusProperties;

    public OrchestratorService(
            SessionManager sessionManager,
            ProjectService projectService,
            TrackerPlugin trackerPlugin,
            WorkspacePlugin workspacePlugin,
            AgentPluginFactory agentPluginFactory,
            RuntimePluginFactory runtimePluginFactory,
            List<NotifierPlugin> notifierPlugins,
            NucleusProperties nucleusProperties) {
        this.sessionManager = sessionManager;
        this.projectService = projectService;
        this.trackerPlugin = trackerPlugin;
        this.workspacePlugin = workspacePlugin;
        this.agentPluginFactory = agentPluginFactory;
        this.runtimePluginFactory = runtimePluginFactory;
        this.notifierPlugins = notifierPlugins;
        this.nucleusProperties = nucleusProperties;
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

    public AgentSession spawn(String projectName, String ticketId) throws Exception {
        Project project = projectService.getProject(projectName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown project: " + projectName));

        String agentType = project.getAgentType() != null ? project.getAgentType()
                : nucleusProperties.getDefaults().getAgent() != null ? nucleusProperties.getDefaults().getAgent()
                : DEFAULT_AGENT_TYPE;
        String runtime = project.getRuntime() != null ? project.getRuntime()
                : nucleusProperties.getDefaults().getRuntime() != null ? nucleusProperties.getDefaults().getRuntime()
                : DEFAULT_RUNTIME;
        String repoPath = project.getPath() != null ? project.getPath() : "";

        AgentPlugin agentPlugin = agentPluginFactory.create(agentType);
        RuntimePlugin runtimePlugin = runtimePluginFactory.create(runtime);

        AgentSession session = new AgentSession(projectName, ticketId);
        session.setAgentType(agentType);
        sessionManager.save(session);

        String issueContext = trackerPlugin.getIssueContext(ticketId);

        String branchName = workspacePlugin.generateBranchName(ticketId, projectName);
        String worktreePath = workspacePlugin.createWorktree(repoPath, branchName);
        session.setBranchName(branchName);
        session.setWorktreePath(worktreePath);

        session.setStatus(AgentSession.Status.RUNNING);

        runtimePlugin.start(session);
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
            String repoPath = projectService.getProject(session.getProjectName())
                    .map(Project::getPath)
                    .orElse("");
            String path = workspacePlugin.restoreWorktree(repoPath, session.getBranchName());
            session.setWorktreePath(path);
        }

        RuntimePlugin runtimePlugin = runtimePluginFactory.create(resolveRuntime(session));
        runtimePlugin.start(session);

        AgentPlugin agentPlugin = agentPluginFactory.create(resolveAgentType(session));
        String issueContext = trackerPlugin.getIssueContext(session.getTicketId());
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

    private String resolveAgentType(AgentSession session) {
        Project project = projectService.getProject(session.getProjectName()).orElse(null);
        if (project != null && project.getAgentType() != null) return project.getAgentType();
        String def = nucleusProperties.getDefaults().getAgent();
        return def != null ? def : DEFAULT_AGENT_TYPE;
    }

    private String resolveRuntime(AgentSession session) {
        Project project = projectService.getProject(session.getProjectName()).orElse(null);
        if (project != null && project.getRuntime() != null) return project.getRuntime();
        String def = nucleusProperties.getDefaults().getRuntime();
        return def != null ? def : DEFAULT_RUNTIME;
    }

    private void notifyAll(String sessionId, String message, NotificationLevel level) throws Exception {
        for (NotifierPlugin notifier : notifierPlugins) {
            notifier.notify(sessionId, message, level);
        }
    }
}
