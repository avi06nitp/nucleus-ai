package com.visa.nucleus.core.service;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.ProjectRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Service for managing Project configurations.
 * Provides CRUD operations and upsert support for loading projects from YAML.
 */
@Service
public class ProjectService {

    private final ProjectRepository repository;

    public ProjectService(ProjectRepository repository) {
        this.repository = repository;
    }

    public List<Project> listProjects() {
        return repository.findAll();
    }

    public Optional<Project> getProject(String name) {
        return repository.findById(name);
    }

    public Project addProject(Project project) {
        return repository.save(project);
    }

    public void removeProject(String name) {
        repository.deleteById(name);
    }

    /**
     * Upserts a project by name — used when loading from agent-orchestrator.yaml on startup.
     */
    public Project upsert(Project project) {
        return repository.save(project);
    }
}
