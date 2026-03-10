package com.visa.nucleus.core;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "reaction_events")
@Data
@NoArgsConstructor
public class ReactionEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    private String sessionId;

    private String eventType;

    @Column(columnDefinition = "TEXT")
    private String payload;

    private LocalDateTime createdAt;
}
