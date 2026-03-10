package com.visa.nucleus.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory repository for AgentSession objects.
 * Provides Spring Data JPA-compatible CRUD operations without requiring a database.
 */
public class AgentSessionRepository {

    private final Map<String, AgentSession> store = new ConcurrentHashMap<>();

    public AgentSession save(AgentSession session) {
        store.put(session.getSessionId(), session);
        return session;
    }

    public Optional<AgentSession> findById(String sessionId) {
        return Optional.ofNullable(store.get(sessionId));
    }

    public List<AgentSession> findAll() {
        return new ArrayList<>(store.values());
    }

    public void deleteById(String sessionId) {
        store.remove(sessionId);
    }

    public boolean existsById(String sessionId) {
        return store.containsKey(sessionId);
    }
}
