// File: src/main/java/com/oddscanner/scanner/ArbFinderService.java

package com.oddscanner.scanner;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.repository.ArbLegRepository;
import com.oddscanner.repository.ArbOpportunityRepository;
import com.oddscanner.repository.OutcomeRepository;
import com.oddscanner.scanner.dto.ArbitrageOpportunityDTO;
import com.oddscanner.scanner.dto.ArbLegDTO;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ArbFinderService {

    private static final Logger log = LoggerFactory.getLogger(ArbFinderService.class);

    private final DSLContext dsl;
    private final ArbCalculator arbCalculator;
    private final OutcomeRepository outcomeRepo;
    private final ArbOpportunityRepository arbOpportunityRepo; // <-- Добавлен
    private final ArbLegRepository arbLegRepo; // <-- Добавлен
    private final ApplicationEventPublisher eventPublisher; // <-- Добавлен для публикации события

    public ArbFinderService(DSLContext dsl,
                            ArbCalculator arbCalculator,
                            OutcomeRepository outcomeRepo,
                            ArbOpportunityRepository arbOpportunityRepo, // <-- Внедряем
                            ArbLegRepository arbLegRepo, // <-- Внедряем
                            ApplicationEventPublisher eventPublisher) { // <-- Внедряем
        this.dsl = dsl;
        this.arbCalculator = arbCalculator;
        this.outcomeRepo = outcomeRepo;
        this.arbOpportunityRepo = arbOpportunityRepo;
        this.arbLegRepo = arbLegRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Сканирует *все* активные исходы (или за определённый промежуток времени) и ищет вилки.
     * В реальности, это может быть вызвано по расписанию или триггериться событием.
     * Для оптимизации, можно фильтровать по времени обновления (updated_at).
     */
    public void scanForArbitrage() {
        log.info("Starting arbitrage scan...");

        // Получаем все *активные* исходы
        List<OutcomesRecord> allActiveOutcomes = dsl.selectFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.IS_ACTIVE.isTrue())
                // .and(Tables.OUTCOMES.UPDATED_AT.greaterThan(someRecentTime)) // Оптимизация: сканировать только недавно обновлённые
                .fetchInto(OutcomesRecord.class);

        log.info("Scanning {} active outcomes for arbitrage...", allActiveOutcomes.size());

        // Группируем исходы по market_id, так как вилка возможна только внутри одного рынка
        Map<Long, List<OutcomesRecord>> groupedOutcomes = allActiveOutcomes.stream()
                .collect(Collectors.groupingBy(OutcomesRecord::getMarketId));

        log.info("Found {} market groups to analyze.", groupedOutcomes.size());

        // Для каждой группы исходов (принадлежащих одному market_id) вызываем ArbCalculator
        for (Map.Entry<Long, List<OutcomesRecord>> entry : groupedOutcomes.entrySet()) {
            Long marketId = entry.getKey();
            List<OutcomesRecord> outcomesForMarket = entry.getValue();

            log.debug("Analyzing market ID: {} with {} outcomes", marketId, outcomesForMarket.size());

            // Проверяем на вилку
            var opportunityOpt = arbCalculator.calculateArbitrage(outcomesForMarket);

            // Обработать найденную вилку (например, сохранить в БД, отправить событие)
            opportunityOpt.ifPresent(this::handleArbitrageOpportunity);
        }

        log.info("Arbitrage scan completed.");
    }

    /**
     * Метод для ручного запуска сканирования (например, через планировщик или по событию).
     */
    public void triggerScan() {
        log.info("Manual/Triggered scan started.");
        scanForArbitrage(); // Вызываем основной метод сканирования
        log.info("Manual/Triggered scan completed.");
    }


    // Метод для обработки найденной вилки
    private void handleArbitrageOpportunity(ArbitrageOpportunityDTO opportunity) {
        log.info("Arbitrage opportunity detected in market {}: Profit {}%", opportunity.getMarketSignature(), opportunity.getProfitPercentage());

        // 1. Проверить, не существует ли уже активная вилка с такой же сигнатурой для этого события
        var existingOpportunityOpt = arbOpportunityRepo.findByEventIdAndMarketSignature(opportunity.getEventId(), opportunity.getMarketSignature());
        if (existingOpportunityOpt.isPresent()) {
            log.debug("Arbitrage opportunity for event {} and signature {} already exists. Updating or skipping.", opportunity.getEventId(), opportunity.getMarketSignature());
            // В реальности можно обновить время found_at или просто пропустить
            // arbOpportunityRepo.updateLastSeen(existingOpportunityOpt.get().getId()); // Метод нужно добавить, если хочешь обновлять
            return;
        }

        // 2. Сохранить основную информацию о вилке
        var opportunityRecord = new com.oddscanner.generated.tables.records.ArbOpportunitiesRecord();
        opportunityRecord.setEventId(opportunity.getEventId());
        opportunityRecord.setMarketSignature(opportunity.getMarketSignature());
        opportunityRecord.setProfitPct(opportunity.getProfitPercentage());
        opportunityRecord.setFoundAt(opportunity.getFoundAt().atOffset(ZoneOffset.UTC)); // Сохраняем как OffsetDateTime
        opportunityRecord.setStatus("ACTIVE"); // Устанавливаем статус ACTIVE

        var savedOpportunityRecord = arbOpportunityRepo.save(opportunityRecord);
        Long savedArbId = savedOpportunityRecord.getId();
        log.debug("Saved new arbitrage opportunity with ID: {}", savedArbId);

        // 3. Сохранить "ноги" вилки
        for (ArbLegDTO leg : opportunity.getLegs()) {
            var legRecord = new com.oddscanner.generated.tables.records.ArbLegsRecord();
            legRecord.setArbId(savedArbId); // Связываем с основной вилкой
            legRecord.setBookmakerId(findBookmakerIdByOutcomeId(leg.getOutcomeId())); // Нужно получить ID букмекера по ID исхода
            legRecord.setMarketId(leg.getMarketId());
            legRecord.setOutcomeId(leg.getOutcomeId());
            legRecord.setOdds(leg.getOdds());
            legRecord.setStakeShare(leg.getStakeShare());

            arbLegRepo.save(legRecord);
        }
        log.debug("Saved {} legs for arbitrage opportunity ID: {}", opportunity.getLegs().size(), savedArbId);

        // 4. Опубликовать событие
        eventPublisher.publishEvent(new ArbitrageFoundEvent(opportunity));
        log.info("Published ArbitrageFoundEvent for opportunity ID: {}", savedArbId);
    }

    // Вспомогательный метод для получения bookmaker_id по outcome_id (нужно выполнить JOIN)
    private Long findBookmakerIdByOutcomeId(Long outcomeId) {
        // SELECT m.bookmaker_id FROM outcomes o JOIN markets m ON o.market_id = m.id WHERE o.id = ?
        return dsl.select(Tables.MARKETS.BOOKMAKER_ID)
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.OUTCOMES.MARKET_ID.eq(Tables.MARKETS.ID))
                .where(Tables.OUTCOMES.ID.eq(outcomeId))
                .fetchOneInto(Long.class);
    }
}