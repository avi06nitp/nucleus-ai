package com.visa.nucleus.core;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AgentSessionRepository extends JpaRepository<AgentSession, String> {
    Optional<AgentSession> findByBranchName(String branchName);
}
