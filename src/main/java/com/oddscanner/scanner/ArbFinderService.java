package com.oddscanner.scanner;

import com.oddscanner.generated.Tables;
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

    private final DSLContext dsl;
    private final ArbCalculator arbCalculator;
    private final OutcomeRepository outcomeRepo;
    private final ArbOpportunityRepository arbOpportunityRepo;
    private final ArbLegRepository arbLegRepo;
    private final ApplicationEventPublisher eventPublisher;

    private static final Set<String> STOP_WORDS = Set.of(
            "fc", "cf", "club", "team", "united", "utd", "city", "real",
            "atletico", "inter", "milan", "barcelona", "madrid", "london",
            "chelsea", "liverpool", "arsenal", "manchester", "man", "juventus",
            "roma", "napoli", "psg", "bayern", "dortmund", "leipzig", "ajax",
            "porto", "benfica", "celtic", "rangers"
    );

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
            normalized = normalized.replaceAll("\\b" + stopWord + "\\b", "").trim();
        }
        return normalized;
    }

    private boolean isSameTeam(String team1, String team2) {
        if (team1 == null || team2 == null) return false;
        String norm1 = normalizeTeamName(team1);
        String norm2 = normalizeTeamName(team2);
        if (norm1.isEmpty() || norm2.isEmpty()) return false;
        return norm1.equals(norm2) || norm1.contains(norm2) || norm2.contains(norm1);
    }

    private boolean isSameMatch(String home1, String away1, String home2, String away2) {
        return (isSameTeam(home1, home2) && isSameTeam(away1, away2)) ||
                (isSameTeam(home1, away2) && isSameTeam(away1, home2));
    }

    /**
     * Главный метод поиска вилок между букмекерами (по сырым названиям команд)
     */
    public void scanCrossBookmakerArbitrage() {
        log.info("=== НАЧАЛО ПОИСКА ВИЛОК МЕЖДУ БУКМЕКЕРАМИ (по сырым названиям) ===");

        var sql = dsl.select(
                        Tables.OUTCOMES.ID.as("outcome_id"),
                        Tables.OUTCOMES.MARKET_ID,
                        Tables.OUTCOMES.OUTCOME_KEY,
                        Tables.OUTCOMES.ODDS,
                        Tables.OUTCOMES.IS_ACTIVE,
                        Tables.MARKETS.EVENT_ID,
                        Tables.MARKETS.BOOKMAKER_ID,
                        Tables.MARKETS.MARKET_TYPE,
                        Tables.BOOKMAKERS.CODE.as("bookmaker_code"),
                        Tables.BOOKMAKERS.NAME.as("bookmaker_name"),
                        Tables.EVENTS.START_TIME,
                        Tables.EVENTS.HOME_TEAM,
                        Tables.EVENTS.AWAY_TEAM
                )
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.OUTCOMES.MARKET_ID.eq(Tables.MARKETS.ID))
                .join(Tables.EVENTS).on(Tables.MARKETS.EVENT_ID.eq(Tables.EVENTS.ID))
                .join(Tables.BOOKMAKERS).on(Tables.MARKETS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                .where(Tables.OUTCOMES.IS_ACTIVE.isTrue())
                .fetch();

        log.info("Найдено {} активных исходов для анализа", sql.size());

        if (sql.isEmpty()) {
            log.warn("Нет активных исходов");
            return;
        }

        // Группируем по сырым названиям команд
        Map<String, List<Map<String, Object>>> eventsGroup = new HashMap<>();

        for (var record : sql) {
            String homeTeam = record.get(Tables.EVENTS.HOME_TEAM);
            String awayTeam = record.get(Tables.EVENTS.AWAY_TEAM);
            OffsetDateTime startTime = record.get(Tables.EVENTS.START_TIME);

            if (homeTeam == null || awayTeam == null) continue;

            String normHome = normalizeTeamName(homeTeam);
            String normAway = normalizeTeamName(awayTeam);

            if (normHome.isEmpty() || normAway.isEmpty()) continue;

            String key = normHome.compareTo(normAway) < 0 ?
                    normHome + "_" + normAway : normAway + "_" + normHome;

            eventsGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(record.intoMap());
        }

        log.info("Найдено {} уникальных матчей (по сырым названиям)", eventsGroup.size());

        int arbCount = 0;
        for (List<Map<String, Object>> outcomes : eventsGroup.values()) {
            // Группируем по market_type + outcome_key
            Map<String, Map<String, Object>> bestOutcomes = new HashMap<>();

            Map<String, List<Map<String, Object>>> byMarketAndOutcome = outcomes.stream()
                    .collect(Collectors.groupingBy(o -> o.get("market_type") + "_" + o.get("outcome_key")));

            for (Map.Entry<String, List<Map<String, Object>>> entry : byMarketAndOutcome.entrySet()) {
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
                    String key = (String) best.get("outcome_key");
                    bestOutcomes.put(key, best);
                }
            }

            if (bestOutcomes.size() >= 2) {
                if (checkAndSaveArbitrage(bestOutcomes)) {
                    arbCount++;
                }
            }
        }

        log.info("=== ПОИСК ВИЛОК ЗАВЕРШЕН, найдено {} вилок ===", arbCount);
    }

    private boolean checkAndSaveArbitrage(Map<String, Map<String, Object>> outcomes) {
        List<Map<String, Object>> outcomeList = new ArrayList<>(outcomes.values());

        BigDecimal inverseSum = BigDecimal.ZERO;
        for (Map<String, Object> outcome : outcomeList) {
            BigDecimal odds = (BigDecimal) outcome.get("odds");
            inverseSum = inverseSum.add(BigDecimal.ONE.divide(odds, MC));
        }

        if (inverseSum.compareTo(BigDecimal.ONE) >= 0) {
            return false;
        }

        BigDecimal profitPct = BigDecimal.ONE.subtract(inverseSum).multiply(BigDecimal.valueOf(100));

        if (profitPct.compareTo(new BigDecimal("0.5")) < 0) {
            return false;
        }

        Long eventId = ((Integer) outcomeList.get(0).get("event_id")).longValue();
        String marketType = (String) outcomeList.get(0).get("market_type");
        String marketSignature = eventId + "_" + marketType + "_" + UUID.randomUUID().toString().substring(0, 8);

        var existing = arbOpportunityRepo.findByEventIdAndMarketSignature(eventId, marketSignature);
        if (existing.isPresent()) {
            return false;
        }

        String homeTeam = (String) outcomeList.get(0).get("home_team");
        String awayTeam = (String) outcomeList.get(0).get("away_team");

        ArbOpportunitiesRecord opportunity = new ArbOpportunitiesRecord();
        opportunity.setEventId(eventId);
        opportunity.setMarketSignature(marketSignature);
        opportunity.setProfitPct(profitPct);
        opportunity.setFoundAt(OffsetDateTime.now(ZoneOffset.UTC));
        opportunity.setStatus("ACTIVE");

        var saved = arbOpportunityRepo.save(opportunity);

        log.info("!!! НАЙДЕНА ВИЛКА !!! {} vs {}: Прибыль {}%", homeTeam, awayTeam, profitPct);

        for (Map<String, Object> outcome : outcomeList) {
            BigDecimal odds = (BigDecimal) outcome.get("odds");
            BigDecimal stakeShare = BigDecimal.ONE.divide(odds, MC).divide(inverseSum, MC);

            ArbLegsRecord leg = new ArbLegsRecord();
            leg.setArbId(saved.getId());
            leg.setBookmakerId(((Integer) outcome.get("bookmaker_id")).longValue());
            leg.setMarketId(((Integer) outcome.get("market_id")).longValue());
            leg.setOutcomeId(((Integer) outcome.get("outcome_id")).longValue());
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
        return true;
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

    public List<Map<String, Object>> getArbsWithDetails() {
        List<Map<String, Object>> result = new ArrayList<>();
        // Ваш существующий код получения вилок с деталями
        return result;
    }
}