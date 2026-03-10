package com.visa.nucleus.core;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "projects")
@Data
@NoArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String name;

    private String repoUrl;

    private String defaultBranch;

    private String jiraProjectKey;

    private String agentType;
}
