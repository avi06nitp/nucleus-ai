package com.visa.nucleus.api;

import com.visa.nucleus.core.Project;
import com.visa.nucleus.core.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProjectControllerTest {

    @Mock private ProjectService projectService;

    private ProjectController controller;

    @BeforeEach
    void setUp() {
        controller = new ProjectController(projectService);
    }

    private Project project(String name) {
        Project p = new Project();
        p.setName(name);
        p.setRepo("org/" + name);
        p.setAgentType("claude");
        p.setRuntime("docker");
        return p;
    }

    // ------------------------------------------------------------------
    // GET /api/projects
    // ------------------------------------------------------------------

    @Test
    void listProjects_returnsAllProjects() {
        when(projectService.listProjects()).thenReturn(List.of(project("alpha"), project("beta")));

        List<Project> result = controller.listProjects();

        assertEquals(2, result.size());
        assertEquals("alpha", result.get(0).getName());
        assertEquals("beta", result.get(1).getName());
    }

    @Test
    void listProjects_returnsEmptyListWhenNoneConfigured() {
        when(projectService.listProjects()).thenReturn(List.of());

        List<Project> result = controller.listProjects();

        assertTrue(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // POST /api/projects
    // ------------------------------------------------------------------

    @Test
    void addProject_returns201WithSavedProject() {
        Project input = project("gamma");
        when(projectService.addProject(input)).thenReturn(input);

        ResponseEntity<Project> response = controller.addProject(input);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("gamma", response.getBody().getName());
        verify(projectService).addProject(input);
    }

    // ------------------------------------------------------------------
    // GET /api/projects/{name}
    // ------------------------------------------------------------------

    @Test
    void getProject_returns200WhenFound() {
        Project p = project("delta");
        when(projectService.getProject("delta")).thenReturn(Optional.of(p));

        ResponseEntity<Project> response = controller.getProject("delta");

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertEquals("delta", response.getBody().getName());
    }

    @Test
    void getProject_returns404WhenNotFound() {
        when(projectService.getProject("missing")).thenReturn(Optional.empty());

        ResponseEntity<Project> response = controller.getProject("missing");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    // ------------------------------------------------------------------
    // DELETE /api/projects/{name}
    // ------------------------------------------------------------------

    @Test
    void removeProject_returns204WhenExists() {
        Project p = project("epsilon");
        when(projectService.getProject("epsilon")).thenReturn(Optional.of(p));

        ResponseEntity<Void> response = controller.removeProject("epsilon");

        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(projectService).removeProject("epsilon");
    }

    @Test
    void removeProject_returns404WhenNotFound() {
        when(projectService.getProject("ghost")).thenReturn(Optional.empty());

        ResponseEntity<Void> response = controller.removeProject("ghost");

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(projectService, never()).removeProject(any());
    }
}
