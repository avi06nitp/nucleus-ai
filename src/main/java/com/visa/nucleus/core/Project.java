package com.visa.nucleus.core;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
public class Project {

    @Id
    private String name;          // e.g. "my-app" — used as primary key

    private String repo;           // "owner/repo"

    private String path;           // local filesystem path

    private String defaultBranch;  // "main"

    private String jiraProjectKey; // "MYAPP"

    private String agentType;      // "claude" or "openai"

    private String runtime;        // "docker" or "tmux"

    private String sessionPrefix;  // short name for branch prefixes

    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
