package com.oddscanner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "markets")
@Data
@NoArgsConstructor
public class Market {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bookmaker_id", nullable = false)
    private Long bookmakerId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String name;

    @Column
    private String line;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
