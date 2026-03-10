package com.visa.nucleus.core;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "agent_sessions")
@Data
@NoArgsConstructor
public class AgentSession {

    public enum Status {
        PENDING, RUNNING, PR_OPEN, IN_REVIEW, COMPLETED, MERGED, FAILED
    }

    @Id
    @Column(name = "id")
    private String sessionId;

    private String projectName;

    private String ticketId;

    private String branchName;

    private String worktreePath;

    private String containerId;

    private String prUrl;

    @Enumerated(EnumType.STRING)
    private Status status;

    private String spawnedBy;

    private LocalDateTime spawnedAt;

    private LocalDateTime updatedAt;

    private String agentType;

    private int ciRetryCount;

    public AgentSession(String projectName, String ticketId) {
        this.sessionId = UUID.randomUUID().toString();
        this.projectName = projectName;
        this.ticketId = ticketId;
        this.status = Status.PENDING;
        this.spawnedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** Constructor that accepts a pre-assigned sessionId (used in tests and factories). */
    public AgentSession(String sessionId, String projectName, String ticketId) {
        this.sessionId = sessionId;
        this.projectName = projectName;
        this.ticketId = ticketId;
        this.status = Status.PENDING;
        this.spawnedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** Convenience constructor for tests where only a known sessionId is needed. */
    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.status = Status.PENDING;
        this.spawnedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void incrementCiRetryCount() {
        this.ciRetryCount++;
        this.updatedAt = LocalDateTime.now();
    }
}
