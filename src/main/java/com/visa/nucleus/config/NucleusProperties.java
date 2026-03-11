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
    private Map<String, ProjectConfig> projects = new HashMap<>();
    private Map<String, ReactionRule> reactions = new HashMap<>();

    // -------------------------------------------------------------------------
    // Nested: Defaults
    // -------------------------------------------------------------------------

    public static class Defaults {
        private String runtime = "docker";
        private String agent = "claude";
        private String workspace = "worktree";
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

        public List<String> getNotifiers() {
            return notifiers;
        }

        public void setNotifiers(List<String> notifiers) {
            this.notifiers = notifiers;
        }
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
