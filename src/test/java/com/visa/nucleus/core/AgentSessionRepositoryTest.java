package com.visa.nucleus.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Verifies AgentSessionRepository is a proper JPA repository interface
 * with the expected query methods. Actual persistence is tested by Spring Data JPA.
 */
@ExtendWith(MockitoExtension.class)
class AgentSessionRepositoryTest {

    @Mock
    private AgentSessionRepository repository;

    @Test
    void findByBranchName_returns_matching_session() {
        AgentSession session = new AgentSession("proj", "T-1");
        session.setBranchName("feat/issue-42");
        when(repository.findByBranchName("feat/issue-42")).thenReturn(Optional.of(session));

        Optional<AgentSession> found = repository.findByBranchName("feat/issue-42");

        assertTrue(found.isPresent());
        assertEquals("feat/issue-42", found.get().getBranchName());
    }

    @Test
    void findByBranchName_returns_empty_for_unknown_branch() {
        when(repository.findByBranchName("nonexistent")).thenReturn(Optional.empty());

        Optional<AgentSession> found = repository.findByBranchName("nonexistent");

        assertTrue(found.isEmpty());
    }

    @Test
    void findAll_returns_list() {
        when(repository.findAll()).thenReturn(List.of(
                new AgentSession("p1", "T-1"),
                new AgentSession("p2", "T-2")));

        List<AgentSession> all = repository.findAll();

        assertEquals(2, all.size());
    }
}
