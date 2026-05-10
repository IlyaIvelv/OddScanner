// File: src/main/java/com/oddscanner/scanner/ArbCalculator.java

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
import java.util.List;
import java.util.stream.Collectors;

@Component
public class ArbCalculator {

    private static final Logger log = LoggerFactory.getLogger(ArbCalculator.class);
    private static final MathContext MC = MathContext.DECIMAL128; // Для точности вычислений

    // Инъецируем DSLContext для выполнения JOIN запросов
    private final DSLContext dsl;

    public ArbCalculator(DSLContext dsl) {
        this.dsl = dsl;
    }

    /**
     * Проверяет, является ли группа исходов (OutcomesRecord) арбитражной возможностью.
     * ВНИМАНИЕ: Перед вызовом этого метода, убедитесь, что все OutcomesRecord
     * принадлежат одному и тому же рынку (market_id), так как метод не проверяет это.
     *
     * @param outcomes Группа исходов, например, [HOME_WIN, AWAY_WIN] или [OVER, UNDER].
     * @return Optional<ArbitrageOpportunityDTO> если вилка найдена, иначе Optional.empty().
     */
    public java.util.Optional<ArbitrageOpportunityDTO> calculateArbitrage(List<OutcomesRecord> outcomes) {
        if (outcomes == null || outcomes.size() < 2) {
            log.debug("Not enough outcomes to calculate arbitrage: {}", outcomes != null ? outcomes.size() : 0);
            return java.util.Optional.empty();
        }

        // Получаем market_id из первого исхода, предполагая, что вся группа принадлежит одному рынку
        Long marketId = outcomes.get(0).getMarketId();
        if (marketId == null) {
            log.error("Outcome record has null marketId: {}", outcomes.get(0).getId());
            return java.util.Optional.empty();
        }

        // Получаем информацию о рынке (event_id, market_type, period, line) через DSLContext
        var marketInfo = dsl.select(Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.PERIOD, Tables.MARKETS.LINE)
                .from(Tables.MARKETS)
                .where(Tables.MARKETS.ID.eq(marketId))
                .fetchOne();

        if (marketInfo == null) {
            log.error("Market not found for ID: {} referenced by outcomes.", marketId);
            return java.util.Optional.empty();
        }

        Long eventId = marketInfo.value1(); // или marketInfo.get(Tables.MARKETS.EVENT_ID)
        String marketType = marketInfo.value2();
        String period = marketInfo.value3();
        BigDecimal line = marketInfo.value4();

        // Сумма обратных величин коэффициентов (1/odd1 + 1/odd2 + ...)
        BigDecimal inverseSum = outcomes.stream()
                .map(outcome -> BigDecimal.ONE.divide(outcome.getOdds(), MC))
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        log.debug("Calculated inverse sum for {} outcomes: {}", outcomes.size(), inverseSum);

        // Если сумма обратных величин < 1, значит, есть вилка
        if (inverseSum.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal profitPct = BigDecimal.ONE.subtract(inverseSum).multiply(BigDecimal.valueOf(100)); // (1 - sum) * 100

            // Сформировать DTO вилки
            String marketSignature = generateMarketSignature(eventId, marketType, period, line);

            List<ArbLegDTO> legs = outcomes.stream()
                    .map(outcome -> {
                        // Рассчитать долю ставки для каждой ноги: (1/odd) / inverseSum
                        BigDecimal stakeShare = BigDecimal.ONE.divide(outcome.getOdds(), MC).divide(inverseSum, MC);
                        return ArbLegDTO.builder()
                                .outcomeId(outcome.getId())
                                .marketId(outcome.getMarketId()) // Уже известен, но можно установить
                                .outcomeKey(outcome.getOutcomeKey())
                                .odds(outcome.getOdds())
                                .stakeShare(stakeShare)
                                .build();
                    })
                    .collect(Collectors.toList());

            // Используем конструктор DTO, который сам проставит foundAt
            ArbitrageOpportunityDTO opportunity = new ArbitrageOpportunityDTO(eventId, marketSignature, profitPct, legs);
            log.info("Arbitrage opportunity found: Profit {}%, Legs: {}", profitPct, legs.size());
            return java.util.Optional.of(opportunity);
        } else {
            log.debug("No arbitrage found. Inverse sum ({}) >= 1.", inverseSum);
            return java.util.Optional.empty();
        }
    }

    // Пример метода генерации сигнатуры рынка
    private String generateMarketSignature(Long eventId, String marketType, String period, BigDecimal line) {
        return String.format("%d_%s_%s_%s", // event_id_type_period_line
                eventId,
                marketType,
                period,
                line != null ? line.toString() : "NULL"
        );
    }
}