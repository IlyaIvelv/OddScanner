package com.oddscanner.scanner;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.Teams;
import com.oddscanner.generated.tables.records.ArbLegsRecord;
import com.oddscanner.generated.tables.records.ArbOpportunitiesRecord;
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

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ArbFinderService {
    private static final Logger log = LoggerFactory.getLogger(ArbFinderService.class);
    private static final MathContext MC = MathContext.DECIMAL128;

    private static final Set<String> STOP_WORDS = Set.of(
            "fc", "cf", "club", "team", "united", "utd", "city", "real",
            "atletico", "inter", "milan", "barcelona", "madrid", "london",
            "chelsea", "liverpool", "arsenal", "manchester", "man", "juventus",
            "roma", "napoli", "psg", "bayern", "dortmund", "leipzig", "ajax",
            "porto", "benfica", "celtic", "rangers"
    );

    private final DSLContext dsl;
    private final ArbCalculator arbCalculator;
    private final OutcomeRepository outcomeRepo;
    private final ArbOpportunityRepository arbOpportunityRepo;
    private final ArbLegRepository arbLegRepo;
    private final ApplicationEventPublisher eventPublisher;

    public ArbFinderService(DSLContext dsl,
                            ArbCalculator arbCalculator,
                            OutcomeRepository outcomeRepo,
                            ArbOpportunityRepository arbOpportunityRepo,
                            ArbLegRepository arbLegRepo,
                            ApplicationEventPublisher eventPublisher) {
        this.dsl = dsl;
        this.arbCalculator = arbCalculator;
        this.outcomeRepo = outcomeRepo;
        this.arbOpportunityRepo = arbOpportunityRepo;
        this.arbLegRepo = arbLegRepo;
        this.eventPublisher = eventPublisher;
    }

    private String normalizeTeamName(String name) {
        if (name == null || name.isEmpty()) return "";
        String normalized = name.toLowerCase()
                .replaceAll("[^a-zа-я0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
        for (String stopWord : STOP_WORDS) {
            normalized = normalized.replaceAll("\\b" + stopWord + "\\b", "");
        }
        return normalized.trim();
    }

    private boolean isSameMatch(String home1, String away1, String home2, String away2) {
        String normHome1 = normalizeTeamName(home1);
        String normAway1 = normalizeTeamName(away1);
        String normHome2 = normalizeTeamName(home2);
        String normAway2 = normalizeTeamName(away2);

        if (normHome1.equals(normHome2) && normAway1.equals(normAway2)) return true;
        if (normHome1.equals(normAway2) && normAway1.equals(normHome2)) return true;
        if ((normHome1.contains(normHome2) || normHome2.contains(normHome1)) &&
                (normAway1.contains(normAway2) || normAway2.contains(normAway1))) return true;

        return false;
    }

    private boolean isSameTime(OffsetDateTime time1, OffsetDateTime time2) {
        if (time1 == null || time2 == null) return false;
        return Math.abs(ChronoUnit.MINUTES.between(time1, time2)) <= 120;
    }

    public void scanCrossBookmakerArbitrage() {
        log.info("=== НАЧАЛО ПОИСКА ВИЛОК МЕЖДУ БУКМЕКЕРАМИ ===");

        Teams homeTeam = Tables.TEAMS.as("home_team");
        Teams awayTeam = Tables.TEAMS.as("away_team");

        var sql = dsl.select(
                        Tables.OUTCOMES.ID.as("outcome_id"),
                        Tables.OUTCOMES.MARKET_ID,
                        Tables.OUTCOMES.OUTCOME_KEY,
                        Tables.OUTCOMES.ODDS,
                        Tables.OUTCOMES.IS_ACTIVE,
                        // Tables.MARKETS.ID.as("market_id"),  // УБРАТЬ - уже есть выше
                        Tables.MARKETS.EVENT_ID,
                        Tables.MARKETS.BOOKMAKER_ID,
                        Tables.MARKETS.MARKET_TYPE,
                        Tables.BOOKMAKERS.CODE.as("bookmaker_code"),
                        Tables.BOOKMAKERS.NAME.as("bookmaker_name"),
                        // Tables.EVENTS.ID.as("event_id"),   // УБРАТЬ - будет дубль
                        Tables.EVENTS.START_TIME,
                        homeTeam.CANONICAL_NAME.as("home_team"),
                        awayTeam.CANONICAL_NAME.as("away_team")
                )
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.OUTCOMES.MARKET_ID.eq(Tables.MARKETS.ID))
                .join(Tables.EVENTS).on(Tables.MARKETS.EVENT_ID.eq(Tables.EVENTS.ID))
                .join(Tables.BOOKMAKERS).on(Tables.MARKETS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                .join(homeTeam).on(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeam.ID))
                .join(awayTeam).on(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeam.ID))
                .where(Tables.OUTCOMES.IS_ACTIVE.isTrue())
                .fetch();

        log.info("Найдено {} активных исходов для анализа", sql.size());

        // Группируем по событиям
        Map<String, List<Map<String, Object>>> matchedEvents = new HashMap<>();

        for (var record : sql) {
            String homeTeamName = record.get("home_team", String.class);
            String awayTeamName = record.get("away_team", String.class);
            OffsetDateTime startTime = record.get("start_time", OffsetDateTime.class);

            boolean matched = false;
            for (Map.Entry<String, List<Map<String, Object>>> entry : matchedEvents.entrySet()) {
                Map<String, Object> first = entry.getValue().get(0);
                String existingHome = (String) first.get("home_team");
                String existingAway = (String) first.get("away_team");
                OffsetDateTime existingTime = (OffsetDateTime) first.get("start_time");

                if (isSameMatch(homeTeamName, awayTeamName, existingHome, existingAway) &&
                        isSameTime(startTime, existingTime)) {
                    entry.getValue().add(record.intoMap());
                    matched = true;
                    break;
                }
            }

            if (!matched) {
                List<Map<String, Object>> newList = new ArrayList<>();
                newList.add(record.intoMap());
                matchedEvents.put(UUID.randomUUID().toString(), newList);
            }
        }

        log.info("Сматчено {} уникальных событий", matchedEvents.size());

        // Анализируем каждое событие
        for (List<Map<String, Object>> outcomes : matchedEvents.values()) {
            Map<String, Map<String, Object>> bestOutcomes = new HashMap<>();

            Map<String, List<Map<String, Object>>> byOutcomeKey = outcomes.stream()
                    .collect(Collectors.groupingBy(o -> (String) o.get("outcome_key")));

            for (Map.Entry<String, List<Map<String, Object>>> entry : byOutcomeKey.entrySet()) {
                String outcomeKey = entry.getKey();
                Map<String, Object> best = null;
                BigDecimal bestOdd = BigDecimal.ZERO;

                for (Map<String, Object> outcome : entry.getValue()) {
                    BigDecimal odds = (BigDecimal) outcome.get("odds");
                    if (odds.compareTo(bestOdd) > 0) {
                        bestOdd = odds;
                        best = outcome;
                    }
                }

                if (best != null) {
                    bestOutcomes.put(outcomeKey, best);
                }
            }

            if (bestOutcomes.size() >= 2) {
                checkAndSaveArbitrage(bestOutcomes);
            }
        }

        log.info("=== ПОИСК ВИЛОК ЗАВЕРШЕН ===");
    }

    private void checkAndSaveArbitrage(Map<String, Map<String, Object>> outcomes) {
        List<Map<String, Object>> outcomeList = new ArrayList<>(outcomes.values());

        BigDecimal inverseSum = BigDecimal.ZERO;
        for (Map<String, Object> outcome : outcomeList) {
            BigDecimal odds = (BigDecimal) outcome.get("odds");
            inverseSum = inverseSum.add(BigDecimal.ONE.divide(odds, MC));
        }

        if (inverseSum.compareTo(BigDecimal.ONE) < 0) {
            BigDecimal profitPct = BigDecimal.ONE.subtract(inverseSum).multiply(BigDecimal.valueOf(100));
            Long eventId = (Long) outcomeList.get(0).get("event_id");
            String marketType = (String) outcomeList.get(0).get("market_type");
            String marketSignature = eventId + "_" + marketType + "_" + UUID.randomUUID().toString().substring(0, 8);

            var existing = arbOpportunityRepo.findByEventIdAndMarketSignature(eventId, marketSignature);
            if (existing.isPresent()) {
                return;
            }

            ArbOpportunitiesRecord opportunity = new ArbOpportunitiesRecord();
            opportunity.setEventId(eventId);
            opportunity.setMarketSignature(marketSignature);
            opportunity.setProfitPct(profitPct);
            opportunity.setFoundAt(OffsetDateTime.now(ZoneOffset.UTC));
            opportunity.setStatus("ACTIVE");

            var saved = arbOpportunityRepo.save(opportunity);
            log.info("!!! НАЙДЕНА ВИЛКА !!! Событие {}: Прибыль {}%", eventId, profitPct);

            for (Map<String, Object> outcome : outcomeList) {
                BigDecimal odds = (BigDecimal) outcome.get("odds");
                BigDecimal stakeShare = BigDecimal.ONE.divide(odds, MC).divide(inverseSum, MC);

                ArbLegsRecord leg = new ArbLegsRecord();
                leg.setArbId(saved.getId());
                leg.setBookmakerId((Long) outcome.get("bookmaker_id"));
                leg.setMarketId((Long) outcome.get("market_id"));
                leg.setOutcomeId((Long) outcome.get("outcome_id"));
                leg.setOdds(odds);
                leg.setStakeShare(stakeShare);

                arbLegRepo.save(leg);

                log.info("  Нога: {} ({}) - коэф {}, доля {}%",
                        outcome.get("outcome_key"),
                        outcome.get("bookmaker_code"),
                        odds,
                        stakeShare.multiply(BigDecimal.valueOf(100)).setScale(2, java.math.RoundingMode.HALF_UP));
            }

            eventPublisher.publishEvent(new ArbitrageFoundEvent(null));
        }
    }

    public void scanForArbitrage() {
        log.info("Starting arbitrage scan...");
        List<OutcomesRecord> allActiveOutcomes = dsl.selectFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.IS_ACTIVE.isTrue())
                .fetchInto(OutcomesRecord.class);
        log.info("Scanning {} active outcomes...", allActiveOutcomes.size());

        Map<Long, List<OutcomesRecord>> groupedOutcomes = allActiveOutcomes.stream()
                .collect(Collectors.groupingBy(OutcomesRecord::getMarketId));

        for (Map.Entry<Long, List<OutcomesRecord>> entry : groupedOutcomes.entrySet()) {
            var opportunityOpt = arbCalculator.calculateArbitrage(entry.getValue());
            opportunityOpt.ifPresent(this::handleArbitrageOpportunity);
        }
        log.info("Arbitrage scan completed.");
    }

    public void triggerScan() {
        log.info("=== ЗАПУСК СКАНЕРА ВИЛОК ===");
        scanCrossBookmakerArbitrage();
        log.info("=== СКАНЕР ВИЛОК ЗАВЕРШИЛ РАБОТУ ===");
    }

    private void handleArbitrageOpportunity(ArbitrageOpportunityDTO opportunity) {
        log.info("Arbitrage opportunity detected: Profit {}%", opportunity.getProfitPercentage());
        var existing = arbOpportunityRepo.findByEventIdAndMarketSignature(
                opportunity.getEventId(), opportunity.getMarketSignature());
        if (existing.isPresent()) return;

        var record = new ArbOpportunitiesRecord();
        record.setEventId(opportunity.getEventId());
        record.setMarketSignature(opportunity.getMarketSignature());
        record.setProfitPct(opportunity.getProfitPercentage());
        record.setFoundAt(opportunity.getFoundAt().atOffset(ZoneOffset.UTC));
        record.setStatus("ACTIVE");
        var saved = arbOpportunityRepo.save(record);

        for (ArbLegDTO leg : opportunity.getLegs()) {
            var legRecord = new ArbLegsRecord();
            legRecord.setArbId(saved.getId());
            legRecord.setBookmakerId(findBookmakerIdByOutcomeId(leg.getOutcomeId()));
            legRecord.setMarketId(leg.getMarketId());
            legRecord.setOutcomeId(leg.getOutcomeId());
            legRecord.setOdds(leg.getOdds());
            legRecord.setStakeShare(leg.getStakeShare());
            arbLegRepo.save(legRecord);
        }
        eventPublisher.publishEvent(new ArbitrageFoundEvent(opportunity));
    }

    private Long findBookmakerIdByOutcomeId(Long outcomeId) {
        return dsl.select(Tables.MARKETS.BOOKMAKER_ID)
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.OUTCOMES.MARKET_ID.eq(Tables.MARKETS.ID))
                .where(Tables.OUTCOMES.ID.eq(outcomeId))
                .fetchOneInto(Long.class);
    }
}