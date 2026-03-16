package com.visa.nucleus.plugins.tracker;

import com.visa.nucleus.config.NucleusProperties;
import com.visa.nucleus.core.plugin.TrackerPlugin;
import org.springframework.stereotype.Component;

/**
 * Factory that returns the appropriate TrackerPlugin implementation
 * based on the configured tracker type.
 *
 * Supported types: "jira", "github", "linear"
 *
 * Configuration (agent-orchestrator.yaml):
 * <pre>
 * nucleus:
 *   tracker:
 *     jira:
 *       baseUrl: https://company.atlassian.net
 *       email: ${JIRA_EMAIL}
 *       apiToken: ${JIRA_API_TOKEN}
 *     linear:
 *       apiKey: ${LINEAR_API_KEY}
 *     github:
 *       token: ${GITHUB_TOKEN}
 *       owner: org
 *       repo: repo-name
 *
 * projects:
 *   my-app:
 *     tracker: github    # jira | github | linear
 * </pre>
 */
@Component
public class TrackerPluginFactory {

    /**
     * Creates a TrackerPlugin using values from {@link NucleusProperties} with environment
     * variable fallback for any missing fields.
     *
     * @param trackerType "jira", "github", or "linear"
     * @param properties  application properties (may be {@code null}, falls back to env vars)
     * @return the matching TrackerPlugin
     * @throws IllegalArgumentException if the tracker type is unsupported or required config is missing
     */
    public TrackerPlugin create(String trackerType, NucleusProperties properties) {
        if (trackerType == null) {
            throw new IllegalArgumentException("trackerType must not be null");
        }
        return switch (trackerType.toLowerCase()) {
            case "github" -> {
                NucleusProperties.TrackerConfig.GithubConfig cfg =
                        properties != null && properties.getTracker() != null
                                ? properties.getTracker().getGithub() : null;
                String token = coalesce(cfg != null ? cfg.getToken() : null, "GITHUB_TOKEN");
                String owner = coalesce(cfg != null ? cfg.getOwner() : null, "GITHUB_OWNER");
                String repo  = coalesce(cfg != null ? cfg.getRepo()  : null, "GITHUB_REPO");
                yield new GitHubIssuesTrackerPlugin(owner, repo, token);
            }
            case "linear" -> {
                NucleusProperties.TrackerConfig.LinearConfig cfg =
                        properties != null && properties.getTracker() != null
                                ? properties.getTracker().getLinear() : null;
                String apiKey = coalesce(cfg != null ? cfg.getApiKey() : null, "LINEAR_API_KEY");
                yield new LinearTrackerPlugin(apiKey);
            }
            case "jira" -> {
                NucleusProperties.TrackerConfig.JiraConfig cfg =
                        properties != null && properties.getTracker() != null
                                ? properties.getTracker().getJira() : null;
                String baseUrl  = coalesce(cfg != null ? cfg.getBaseUrl()  : null, "JIRA_BASE_URL");
                String email    = coalesce(cfg != null ? cfg.getEmail()    : null, "JIRA_EMAIL");
                String apiToken = coalesce(cfg != null ? cfg.getApiToken() : null, "JIRA_API_TOKEN");
                yield new JiraTrackerPlugin(baseUrl, email, apiToken);
            }
            default -> throw new IllegalArgumentException("Unknown tracker: " + trackerType);
        };
    }

    /**
     * Creates a TrackerPlugin using only environment variables (no properties).
     * Provided for convenience; prefer {@link #create(String, NucleusProperties)}.
     */
    public TrackerPlugin create(String trackerType) {
        return create(trackerType, null);
    }

    /**
     * Returns {@code value} if non-blank, otherwise reads {@code envVar} from the environment.
     *
     * @throws IllegalArgumentException if both {@code value} and the env var are blank
     */
    private String coalesce(String value, String envVar) {
        if (value != null && !value.isBlank() && !value.startsWith("${")) {
            return value;
        }
        String env = System.getenv(envVar);
        if (env != null && !env.isBlank()) {
            return env;
        }
        throw new IllegalArgumentException(
                "Required config is missing: set nucleus.tracker.<type>.<field> or " + envVar);
    }
}
