package com.oddscanner.scanner;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.scanner.dto.ArbitrageOpportunityDTO;
import com.oddscanner.scanner.dto.ArbLegDTO;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.*;

@Component
public class ArbCalculator {
    private static final Logger log = LoggerFactory.getLogger(ArbCalculator.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    private final DSLContext dsl;

    public ArbCalculator(DSLContext dsl) {
        this.dsl = dsl;
    }

    // Поиск вилок внутри одного рынка (для одного букмекера)
    public Optional<ArbitrageOpportunityDTO> calculateArbitrage(List<OutcomesRecord> outcomes) {
        if (outcomes == null || outcomes.size() < 2) {
            return Optional.empty();
        }

        BigDecimal inverseSum = outcomes.stream()
                .map(outcome -> BigDecimal.ONE.divide(outcome.getOdds(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (inverseSum.compareTo(BigDecimal.ONE) >= 0) {
            return Optional.empty();
        }

        Long marketId = outcomes.get(0).getMarketId();
        var marketInfo = dsl.select(Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE)
                .from(Tables.MARKETS)
                .where(Tables.MARKETS.ID.eq(marketId))
                .fetchOne();

        if (marketInfo == null) {
            return Optional.empty();
        }

        Long eventId = marketInfo.value1();
        String marketType = marketInfo.value2();
        BigDecimal profitPct = BigDecimal.ONE.subtract(inverseSum).multiply(BigDecimal.valueOf(100));

        List<ArbLegDTO> legs = outcomes.stream()
                .map(outcome -> ArbLegDTO.builder()
                        .outcomeId(outcome.getId())
                        .marketId(outcome.getMarketId())
                        .outcomeKey(outcome.getOutcomeKey())
                        .odds(outcome.getOdds())
                        .stakeShare(BigDecimal.ONE.divide(outcome.getOdds(), MC).divide(inverseSum, MC))
                        .build())
                .collect(java.util.stream.Collectors.toList());

        ArbitrageOpportunityDTO opportunity = ArbitrageOpportunityDTO.builder()
                .eventId(eventId)
                .marketSignature(marketType + "_" + marketId)
                .profitPercentage(profitPct)
                .legs(legs)
                .build();

        log.info("Arbitrage found! Profit: {}%", profitPct);
        return Optional.of(opportunity);
    }
}