package com.oddscanner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "outcomes")
@Data
@NoArgsConstructor
public class Outcome {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "market_id", nullable = false)
    private Long marketId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false, precision = 10, scale = 4)
    private BigDecimal odds;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
