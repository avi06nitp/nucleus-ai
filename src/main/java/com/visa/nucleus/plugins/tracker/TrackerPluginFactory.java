package com.visa.nucleus.plugins.tracker;

import com.visa.nucleus.core.plugin.TrackerPlugin;

/**
 * Factory that returns the appropriate TrackerPlugin implementation
 * based on the configured tracker type.
 *
 * Supported types: "jira", "github", "linear"
 *
 * Configuration (agent-orchestrator.yaml):
 * <pre>
 * projects:
 *   my-app:
 *     tracker: github    # jira | github | linear
 * </pre>
 */
public class TrackerPluginFactory {

    /**
     * Creates a TrackerPlugin for the given tracker type.
     *
     * <p>For "github": reads {@code GITHUB_TOKEN}, {@code GITHUB_OWNER}, {@code GITHUB_REPO} from environment.
     * <p>For "linear": reads {@code LINEAR_API_KEY} from environment.
     * <p>For "jira": reads {@code JIRA_BASE_URL}, {@code JIRA_EMAIL}, {@code JIRA_API_TOKEN} from environment.
     *
     * @param trackerType "jira", "github", or "linear"
     * @return the matching TrackerPlugin
     * @throws IllegalArgumentException if the tracker type is unsupported or required env vars are missing
     */
    public TrackerPlugin create(String trackerType) {
        if (trackerType == null) {
            throw new IllegalArgumentException("trackerType must not be null");
        }
        return switch (trackerType.toLowerCase()) {
            case "github" -> {
                String token = requireEnv("GITHUB_TOKEN");
                String owner = requireEnv("GITHUB_OWNER");
                String repo = requireEnv("GITHUB_REPO");
                yield new GitHubIssuesTrackerPlugin(owner, repo, token);
            }
            case "linear" -> {
                String apiKey = requireEnv("LINEAR_API_KEY");
                yield new LinearTrackerPlugin(apiKey);
            }
            case "jira" -> {
                String baseUrl = requireEnv("JIRA_BASE_URL");
                String email = requireEnv("JIRA_EMAIL");
                String apiToken = requireEnv("JIRA_API_TOKEN");
                yield new JiraTrackerPlugin(baseUrl, email, apiToken);
            }
            default -> throw new IllegalArgumentException("Unknown tracker: " + trackerType);
        };
    }

    private String requireEnv(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required environment variable not set: " + name);
        }
        return value;
    }
}
