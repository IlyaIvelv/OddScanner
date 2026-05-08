package com.oddscanner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "scrape_jobs")
@Data
@NoArgsConstructor
public class ScrapeJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "bookmaker_id", nullable = false)
    private Long bookmakerId;

    @Column(nullable = false)
    private String status = "pending";

    @Column(name = "events_found")
    private Integer eventsFound = 0;

    @Column(name = "markets_found")
    private Integer marketsFound = 0;

    @Column
    private String error;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "finished_at")
    private Instant finishedAt;
}
