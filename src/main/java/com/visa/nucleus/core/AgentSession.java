package com.visa.nucleus.core;

import java.time.Instant;
import java.util.UUID;

public class AgentSession {

    public enum Status {
        PENDING, RUNNING, COMPLETED, FAILED, MERGED
    }

    private final String sessionId;
    private final String projectName;
    private final String ticketId;
    private String branchName;
    private String worktreePath;
    private String containerId;
    private Status status;
    private int ciRetryCount;
    private final Instant createdAt;
    private Instant updatedAt;

    public AgentSession(String projectName, String ticketId) {
        this.sessionId = UUID.randomUUID().toString();
        this.projectName = projectName;
        this.ticketId = ticketId;
        this.status = Status.PENDING;
        this.ciRetryCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    /** Convenience constructor for tests and simple use-cases where ticketId is not yet known. */
    public AgentSession(String sessionId) {
        this.sessionId = sessionId;
        this.projectName = null;
        this.ticketId = null;
        this.status = Status.PENDING;
        this.ciRetryCount = 0;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    public String getSessionId() { return sessionId; }
    public String getProjectName() { return projectName; }
    public String getTicketId() { return ticketId; }
    public String getBranchName() { return branchName; }
    public String getWorktreePath() { return worktreePath; }
    public String getContainerId() { return containerId; }
    public Status getStatus() { return status; }
    public int getCiRetryCount() { return ciRetryCount; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setBranchName(String branchName) {
        this.branchName = branchName;
        this.updatedAt = Instant.now();
    }

    public void setWorktreePath(String worktreePath) {
        this.worktreePath = worktreePath;
        this.updatedAt = Instant.now();
    }

    public void setContainerId(String containerId) {
        this.containerId = containerId;
        this.updatedAt = Instant.now();
    }

    public void setStatus(Status status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void incrementCiRetryCount() {
        this.ciRetryCount++;
        this.updatedAt = Instant.now();
    }
}
