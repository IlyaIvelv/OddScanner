package com.oddscanner.model;

import com.oddscanner.domain.ArbLeg;
import io.hypersistence.utils.hibernate.type.json.JsonType;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Type;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Entity
@Table(name = "arbs")
@Data
@NoArgsConstructor
public class Arb {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "market_type", nullable = false)
    private String marketType;

    @Column(name = "profit_pct", nullable = false, precision = 8, scale = 4)
    private BigDecimal profitPct;

    @Type(JsonType.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<ArbLeg> legs;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "found_at", nullable = false, updatable = false)
    private Instant foundAt = Instant.now();

    @Column(name = "expires_at")
    private Instant expiresAt;
}
