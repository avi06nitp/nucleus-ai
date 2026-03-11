package com.visa.nucleus.core.service;

import com.visa.nucleus.core.AgentSession;
import com.visa.nucleus.core.AgentSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Manages CRUD operations on AgentSession via AgentSessionRepository.
 */
@Service
public class SessionManager {

    private final AgentSessionRepository repository;

    public SessionManager(AgentSessionRepository repository) {
        this.repository = repository;
    }

    public List<AgentSession> getAllSessions() {
        return repository.findAll();
    }

    public Optional<AgentSession> getSession(String sessionId) {
        return repository.findById(sessionId);
    }

    public AgentSession save(AgentSession session) {
        return repository.save(session);
    }

    public void updateStatus(String sessionId, AgentSession.Status status) {
        repository.findById(sessionId).ifPresent(session -> {
            session.setStatus(status);
            repository.save(session);
        });
    }

    public void deleteSession(String sessionId) {
        repository.deleteById(sessionId);
    }
}
