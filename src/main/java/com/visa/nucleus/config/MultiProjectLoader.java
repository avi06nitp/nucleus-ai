package com.visa.nucleus.config;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import org.yaml.snakeyaml.Yaml;

/**
 * Loads project configurations from {@code agent-orchestrator.yaml} on application startup
 * and upserts them into the projects table.
 *
 * <p>Expected YAML structure under {@code projects:}:
 * <pre>
 * projects:
 *   my-app:
 *     repo: owner/my-app
 *     path: ~/my-app
 *     defaultBranch: main
 *     jiraProjectKey: MYAPP
 *     agentType: claude
 *     runtime: docker
 *     sessionPrefix: ma
 * </pre>
 */
@Component
public class MultiProjectLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MultiProjectLoader.class);

    private final ProjectService projectService;

    @Value("${nucleus.config.file:agent-orchestrator.yaml}")
    private String configFile;

    public MultiProjectLoader(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    public void run(ApplicationArguments args) {
        Path configPath = resolveConfigPath();
        if (configPath == null || !Files.exists(configPath)) {
            log.info("No agent-orchestrator.yaml found at {}; skipping project auto-load.", configFile);
            return;
        }

        try (InputStream in = Files.newInputStream(configPath)) {
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);
            if (root == null || !root.containsKey("projects")) {
                log.info("agent-orchestrator.yaml has no 'projects' section; nothing to load.");
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> projects = (Map<String, Object>) root.get("projects");

            int count = 0;
            for (Map.Entry<String, Object> entry : projects.entrySet()) {
                String projectName = entry.getKey();
                @SuppressWarnings("unchecked")
                Map<String, Object> cfg = entry.getValue() instanceof Map
                        ? (Map<String, Object>) entry.getValue()
                        : new LinkedHashMap<>();

                Project project = new Project();
                project.setName(projectName);
                project.setRepo(str(cfg, "repo"));
                project.setPath(expandHome(str(cfg, "path")));
                project.setDefaultBranch(str(cfg, "defaultBranch"));
                project.setJiraProjectKey(str(cfg, "jiraProjectKey"));
                project.setAgentType(str(cfg, "agentType"));
                project.setRuntime(str(cfg, "runtime"));
                project.setSessionPrefix(str(cfg, "sessionPrefix"));

                projectService.upsert(project);
                count++;
                log.info("Loaded project '{}' from {}", projectName, configPath);
            }
            log.info("Loaded {} project(s) from agent-orchestrator.yaml", count);
        } catch (Exception e) {
            log.warn("Failed to load projects from {}: {}", configPath, e.getMessage());
        }
    }

    private Path resolveConfigPath() {
        // Try as absolute path first, then relative to user home
        Path p = Paths.get(configFile);
        if (p.isAbsolute()) return p;

        // Try relative to current working directory
        Path cwd = Paths.get(System.getProperty("user.dir")).resolve(configFile);
        if (Files.exists(cwd)) return cwd;

        // Try relative to user home
        Path home = Paths.get(System.getProperty("user.home")).resolve(configFile);
        if (Files.exists(home)) return home;

        return cwd; // Return the cwd path (existence is checked by caller)
    }

    private String str(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : null;
    }

    private String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
