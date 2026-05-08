package com.oddscanner.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Entity
@Table(name = "scheduler_config")
@Data
@NoArgsConstructor
public class SchedulerConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cron_expression", nullable = false)
    private String cronExpression = "0 */5 * * * *";

    @Column(name = "is_enabled", nullable = false)
    private boolean isEnabled = true;

    @Column(name = "min_profit_pct", nullable = false)
    private int minProfitPct = 0;

    @Column(name = "last_run_at")
    private Instant lastRunAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
