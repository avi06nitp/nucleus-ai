package com.visa.nucleus.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class AgentSessionRepositoryTest {

    private AgentSessionRepository repository;

    @BeforeEach
    void setUp() {
        repository = new AgentSessionRepository();
    }

    @Test
    void save_and_findById_roundtrip() {
        AgentSession session = new AgentSession("project-x", "TICKET-1");
        repository.save(session);

        Optional<AgentSession> found = repository.findById(session.getSessionId());
        assertTrue(found.isPresent());
        assertEquals(session.getSessionId(), found.get().getSessionId());
    }

    @Test
    void findById_returns_empty_for_unknown_id() {
        Optional<AgentSession> found = repository.findById("nonexistent");
        assertTrue(found.isEmpty());
    }

    @Test
    void findAll_returns_all_saved_sessions() {
        repository.save(new AgentSession("proj-a", "T-1"));
        repository.save(new AgentSession("proj-b", "T-2"));

        List<AgentSession> all = repository.findAll();
        assertEquals(2, all.size());
    }

    @Test
    void deleteById_removes_session() {
        AgentSession session = new AgentSession("proj", "T-3");
        repository.save(session);
        repository.deleteById(session.getSessionId());

        assertFalse(repository.existsById(session.getSessionId()));
    }

    @Test
    void existsById_returns_true_for_saved_session() {
        AgentSession session = new AgentSession("proj", "T-4");
        repository.save(session);

        assertTrue(repository.existsById(session.getSessionId()));
    }
}
