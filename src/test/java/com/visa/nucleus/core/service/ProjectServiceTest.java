package com.visa.nucleus.core.service;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectServiceTest {

    @Mock private ProjectRepository repository;

    private ProjectService service;

    @BeforeEach
    void setUp() {
        service = new ProjectService(repository);
    }

    private Project project(String name) {
        Project p = new Project();
        p.setName(name);
        p.setAgentType("claude");
        p.setRuntime("docker");
        return p;
    }

    @Test
    void listProjects_delegatesToRepository() {
        when(repository.findAll()).thenReturn(List.of(project("a"), project("b")));

        List<Project> result = service.listProjects();

        assertEquals(2, result.size());
        verify(repository).findAll();
    }

    @Test
    void getProject_returnsProjectWhenPresent() {
        when(repository.findById("my-app")).thenReturn(Optional.of(project("my-app")));

        Optional<Project> result = service.getProject("my-app");

        assertTrue(result.isPresent());
        assertEquals("my-app", result.get().getName());
    }

    @Test
    void getProject_returnsEmptyWhenAbsent() {
        when(repository.findById("unknown")).thenReturn(Optional.empty());

        Optional<Project> result = service.getProject("unknown");

        assertTrue(result.isEmpty());
    }

    @Test
    void addProject_savesAndReturnsProject() {
        Project p = project("new-proj");
        when(repository.save(p)).thenReturn(p);

        Project saved = service.addProject(p);

        assertEquals("new-proj", saved.getName());
        verify(repository).save(p);
    }

    @Test
    void removeProject_deletesById() {
        service.removeProject("old-proj");

        verify(repository).deleteById("old-proj");
    }

    @Test
    void upsert_savesProjectRegardlessOfExistence() {
        Project p = project("existing");
        when(repository.save(p)).thenReturn(p);

        Project result = service.upsert(p);

        assertEquals("existing", result.getName());
        verify(repository).save(p);
    }

    @Test
    void upsert_canOverwriteExistingProject() {
        Project original = project("proj");
        original.setAgentType("claude");

        Project updated = project("proj");
        updated.setAgentType("openai");

        when(repository.save(updated)).thenReturn(updated);

        Project result = service.upsert(updated);

        assertEquals("openai", result.getAgentType());
        verify(repository).save(updated);
    }
}
