package com.visa.nucleus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Top-level configuration properties for Nucleus AI, populated from
 * {@code agent-orchestrator.yaml} (or {@code application.yml} as fallback).
 *
 * <p>The external config file location is resolved in this order:
 * <ol>
 *   <li>Path provided by the {@code NUCLEUS_CONFIG} environment variable.</li>
 *   <li>{@code ./agent-orchestrator.yaml} in the working directory.</li>
 *   <li>Spring {@code application.yml} values under the {@code nucleus} prefix.</li>
 * </ol>
 */
@ConfigurationProperties(prefix = "nucleus")
public class NucleusProperties {

    private int port = 3000;
    private Defaults defaults = new Defaults();
    private TrackerConfig tracker = new TrackerConfig();
    private Map<String, ProjectConfig> projects = new HashMap<>();
    private Map<String, ReactionRule> reactions = new HashMap<>();

    // -------------------------------------------------------------------------
    // Nested: Defaults
    // -------------------------------------------------------------------------

    public static class Defaults {
        private String runtime = "docker";
        private String agent = "claude";
        private String workspace = "worktree";
        private String tracker = "jira";
        private List<String> notifiers = new ArrayList<>(List.of("teams"));

        public String getRuntime() {
            return runtime;
        }

        public void setRuntime(String runtime) {
            this.runtime = runtime;
        }

        public String getAgent() {
            return agent;
        }

        public void setAgent(String agent) {
            this.agent = agent;
        }

        public String getWorkspace() {
            return workspace;
        }

        public void setWorkspace(String workspace) {
            this.workspace = workspace;
        }

        public String getTracker() {
            return tracker;
        }

        public void setTracker(String tracker) {
            this.tracker = tracker;
        }

        public List<String> getNotifiers() {
            return notifiers;
        }

        public void setNotifiers(List<String> notifiers) {
            this.notifiers = notifiers;
        }
    }

    // -------------------------------------------------------------------------
    // Nested: TrackerConfig
    // -------------------------------------------------------------------------

    public static class TrackerConfig {
        private JiraConfig jira = new JiraConfig();
        private LinearConfig linear = new LinearConfig();
        private GithubConfig github = new GithubConfig();

        public static class JiraConfig {
            private String baseUrl;
            private String email;
            private String apiToken;

            public String getBaseUrl() { return baseUrl; }
            public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

            public String getEmail() { return email; }
            public void setEmail(String email) { this.email = email; }

            public String getApiToken() { return apiToken; }
            public void setApiToken(String apiToken) { this.apiToken = apiToken; }
        }

        public static class LinearConfig {
            private String apiKey;

            public String getApiKey() { return apiKey; }
            public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        }

        public static class GithubConfig {
            private String token;
            private String owner;
            private String repo;

            public String getToken() { return token; }
            public void setToken(String token) { this.token = token; }

            public String getOwner() { return owner; }
            public void setOwner(String owner) { this.owner = owner; }

            public String getRepo() { return repo; }
            public void setRepo(String repo) { this.repo = repo; }
        }

        public JiraConfig getJira() { return jira; }
        public void setJira(JiraConfig jira) { this.jira = jira; }

        public LinearConfig getLinear() { return linear; }
        public void setLinear(LinearConfig linear) { this.linear = linear; }

        public GithubConfig getGithub() { return github; }
        public void setGithub(GithubConfig github) { this.github = github; }
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public Defaults getDefaults() {
        return defaults;
    }

    public void setDefaults(Defaults defaults) {
        this.defaults = defaults;
    }

    public TrackerConfig getTracker() {
        return tracker;
    }

    public void setTracker(TrackerConfig tracker) {
        this.tracker = tracker;
    }

    public Map<String, ProjectConfig> getProjects() {
        return projects;
    }

    public void setProjects(Map<String, ProjectConfig> projects) {
        this.projects = projects;
    }

    public Map<String, ReactionRule> getReactions() {
        return reactions;
    }

    public void setReactions(Map<String, ReactionRule> reactions) {
        this.reactions = reactions;
    }
}
