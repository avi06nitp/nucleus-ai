package com.visa.nucleus.api;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.service.ProjectService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * REST API for managing project configurations.
 *
 * GET    /api/projects          → list all configured projects
 * POST   /api/projects          → add a new project at runtime
 * GET    /api/projects/{name}   → get project config
 * DELETE /api/projects/{name}   → remove project
 */
@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private final ProjectService projectService;

    public ProjectController(ProjectService projectService) {
        this.projectService = projectService;
    }

    @GetMapping
    public List<Project> listProjects() {
        return projectService.listProjects();
    }

    @PostMapping
    public ResponseEntity<Project> addProject(@RequestBody Project project) {
        Project saved = projectService.addProject(project);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    @GetMapping("/{name}")
    public ResponseEntity<Project> getProject(@PathVariable String name) {
        return projectService.getProject(name)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{name}")
    public ResponseEntity<Void> removeProject(@PathVariable String name) {
        if (projectService.getProject(name).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        projectService.removeProject(name);
        return ResponseEntity.noContent().build();
    }
}
