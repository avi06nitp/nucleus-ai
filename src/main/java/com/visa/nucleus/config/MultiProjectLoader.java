package com.visa.nucleus.config;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.service.ProjectService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * On startup, upserts every project defined in {@code agent-orchestrator.yaml}
 * (already parsed into {@link NucleusProperties}) into the {@code projects} table.
 *
 * <p>This makes the REST API ({@code GET /api/projects}) immediately return all
 * YAML-configured projects without requiring a manual {@code POST /api/projects}.</p>
 */
@Component
public class MultiProjectLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MultiProjectLoader.class);

    private final NucleusProperties nucleusProperties;
    private final ProjectService projectService;

    public MultiProjectLoader(NucleusProperties nucleusProperties, ProjectService projectService) {
        this.nucleusProperties = nucleusProperties;
        this.projectService = projectService;
    }

    @Override
    public void run(ApplicationArguments args) {
        var projects = nucleusProperties.getProjects();
        if (projects == null || projects.isEmpty()) {
            log.info("No projects defined in agent-orchestrator.yaml; skipping auto-load.");
            return;
        }

        int count = 0;
        for (var entry : projects.entrySet()) {
            String name = entry.getKey();
            ProjectConfig cfg = entry.getValue();

            Project project = new Project();
            project.setName(name);
            project.setRepo(cfg.getRepo());
            project.setPath(expandHome(cfg.getPath()));
            project.setDefaultBranch(cfg.getDefaultBranch());
            project.setJiraProjectKey(cfg.getJiraProjectKey());
            project.setAgentType(cfg.getAgentType());
            project.setRuntime(cfg.getRuntime());
            project.setSessionPrefix(cfg.getSessionPrefix());

            projectService.upsert(project);
            count++;
            log.info("Upserted project '{}' from agent-orchestrator.yaml", name);
        }
        log.info("Loaded {} project(s) from agent-orchestrator.yaml into the database", count);
    }

    private String expandHome(String path) {
        if (path != null && path.startsWith("~")) {
            return System.getProperty("user.home") + path.substring(1);
        }
        return path;
    }
}
