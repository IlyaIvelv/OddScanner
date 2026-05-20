package com.oddscanner.scanner;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.ArbLegsRecord;
import com.oddscanner.generated.tables.records.ArbOpportunitiesRecord;
import com.oddscanner.repository.ArbLegRepository;
import com.oddscanner.repository.ArbOpportunityRepository;
import com.oddscanner.scanner.dto.ArbOpportunityWithDetailsDto;
import com.oddscanner.scanner.dto.ArbOpportunityWithEventDto;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class ArbFinderService {
    private static final Logger log = LoggerFactory.getLogger(ArbFinderService.class);
    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal MIN_PROFIT_PERCENT = new BigDecimal("0.5"); // 0.5% минимальная прибыль

    private final DSLContext dsl;
    private final ArbOpportunityRepository arbOpportunityRepo;
    private final ArbLegRepository arbLegRepo;
    private final ApplicationEventPublisher eventPublisher;

    public ArbFinderService(DSLContext dsl,
                            ArbOpportunityRepository arbOpportunityRepo,
                            ArbLegRepository arbLegRepo,
                            ApplicationEventPublisher eventPublisher) {
        this.dsl = dsl;
        this.arbOpportunityRepo = arbOpportunityRepo;
        this.arbLegRepo = arbLegRepo;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Главный метод поиска вилок
     */
    public void scanForArbitrage() {
        log.info("=== ЗАПУСК СКАНЕРА ВИЛОК ===");

        // 1. Получаем все активные исходы с информацией о рынках и событиях
        var outcomes = dsl.select(
                        Tables.OUTCOMES.ID.as("outcome_id"),
                        Tables.OUTCOMES.MARKET_ID,
                        Tables.OUTCOMES.OUTCOME_KEY,
                        Tables.OUTCOMES.ODDS,
                        Tables.MARKETS.EVENT_ID,
                        Tables.MARKETS.MARKET_TYPE,
                        Tables.MARKETS.PERIOD,
                        Tables.MARKETS.BOOKMAKER_ID,
                        Tables.BOOKMAKERS.CODE.as("bookmaker_code"),
                        Tables.BOOKMAKERS.NAME.as("bookmaker_name")
                )
                .from(Tables.OUTCOMES)
                .join(Tables.MARKETS).on(Tables.OUTCOMES.MARKET_ID.eq(Tables.MARKETS.ID))
                .join(Tables.BOOKMAKERS).on(Tables.MARKETS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                .where(Tables.OUTCOMES.IS_ACTIVE.isTrue())
                .fetch();

        log.info("Найдено {} активных исходов для анализа", outcomes.size());

        if (outcomes.isEmpty()) {
            log.warn("Нет активных исходов");
            return;
        }

        // 2. Группируем по (event_id, market_type, period)
        Map<String, List<Map<String, Object>>> groups = new HashMap<>();

        for (var record : outcomes) {
            Long eventId = record.get(Tables.MARKETS.EVENT_ID);
            String marketType = record.get(Tables.MARKETS.MARKET_TYPE);
            String period = record.get(Tables.MARKETS.PERIOD);
            if (period == null) period = "";

            String key = eventId + "_" + marketType + "_" + period;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(record.intoMap());
        }

        log.info("Групп для анализа: {}", groups.size());

        int arbCount = 0;
        for (List<Map<String, Object>> group : groups.values()) {
            // Быстрая проверка: есть ли в группе хотя бы 2 разных букмекера
            Set<String> bookmakersInGroup = group.stream()
                    .map(o -> (String) o.get("bookmaker_code"))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());

            if (bookmakersInGroup.size() < 2) {
                log.debug("Группа пропущена: только {} (нет вилки между разными БК)", bookmakersInGroup);
                continue;
            }

            if (checkAndSaveArbitrage(group)) {
                arbCount++;
            }
        }

        log.info("=== ПОИСК ВИЛОК ЗАВЕРШЕН, найдено {} вилок ===", arbCount);
    }

    /**
     * Проверяет группу исходов на наличие вилки и сохраняет
     */
    private boolean checkAndSaveArbitrage(List<Map<String, Object>> outcomes) {
        if (outcomes.size() < 2) return false;

        // 1. Группируем по outcome_key и выбираем максимальный коэффициент
        Map<String, Map<String, Object>> bestOutcomes = new HashMap<>();

        Map<String, List<Map<String, Object>>> byOutcomeKey = outcomes.stream()
                .collect(Collectors.groupingBy(o -> (String) o.get("outcome_key")));

        for (Map.Entry<String, List<Map<String, Object>>> entry : byOutcomeKey.entrySet()) {
            Map<String, Object> best = null;
            BigDecimal bestOdds = BigDecimal.ZERO;

            for (Map<String, Object> outcome : entry.getValue()) {
                BigDecimal odds = (BigDecimal) outcome.get("odds");
                if (odds.compareTo(bestOdds) > 0) {
                    bestOdds = odds;
                    best = outcome;
                }
            }
            if (best != null) {
                bestOutcomes.put(entry.getKey(), best);
            }
        }

        // 2. ПРОВЕРКА: исходы из разных букмекеров
        Set<String> bookmakerCodes = new HashSet<>();
        for (Map<String, Object> outcome : bestOutcomes.values()) {
            String bookmakerCode = (String) outcome.get("bookmaker_code");
            if (bookmakerCode != null) {
                bookmakerCodes.add(bookmakerCode);
            }
        }

        // Если все исходы из одного букмекера — это не реализуемая вилка
        if (bookmakerCodes.size() < 2) {
            log.debug("❌ Вилка отклонена: все исходы из одного букмекера ({})",
                    bookmakerCodes.iterator().next());
            return false;
        }

        log.info("🔍 Потенциальная вилка из {} букмекеров: {}", bookmakerCodes.size(), bookmakerCodes);

        // 3. Для рынка WIN_DRAW_WIN нужно 3 исхода
        String marketType = (String) outcomes.get(0).get("market_type");
        if ("WIN_DRAW_WIN".equals(marketType) && bestOutcomes.size() < 3) {
            log.debug("Недостаточно исходов для WIN_DRAW_WIN: {}", bestOutcomes.size());
            return false;
        }

        // 4. Сортируем исходы в порядке HOME_WIN, DRAW, AWAY_WIN для читаемости
        List<Map<String, Object>> sortedOutcomes = new ArrayList<>(bestOutcomes.values());
        if ("WIN_DRAW_WIN".equals(marketType)) {
            sortedOutcomes.sort(Comparator.comparing(o -> {
                String key = (String) o.get("outcome_key");
                if ("HOME_WIN".equals(key)) return 0;
                if ("DRAW".equals(key)) return 1;
                if ("AWAY_WIN".equals(key)) return 2;
                return 3;
            }));
        }

        // 5. Рассчитываем сумму обратных величин
        BigDecimal inverseSum = BigDecimal.ZERO;
        for (Map<String, Object> outcome : sortedOutcomes) {
            BigDecimal odds = (BigDecimal) outcome.get("odds");
            inverseSum = inverseSum.add(BigDecimal.ONE.divide(odds, MC));
        }

        // 6. Проверяем, есть ли вилка (сумма < 1)
        if (inverseSum.compareTo(BigDecimal.ONE) >= 0) {
            return false;
        }

        // 7. Рассчитываем прибыль
        BigDecimal profitPct = BigDecimal.ONE.subtract(inverseSum).multiply(BigDecimal.valueOf(100));

        if (profitPct.compareTo(MIN_PROFIT_PERCENT) < 0) {
            log.debug("Прибыль {}% ниже минимальной {}%", profitPct, MIN_PROFIT_PERCENT);
            return false;
        }

        // 8. Получаем информацию о событии
        Long eventId = getLongValue(sortedOutcomes.get(0).get("event_id"));
        String marketSignature = eventId + "_" + marketType + "_" + System.currentTimeMillis();

        // 9. Проверяем, не существовала ли уже активная вилка для этого события
        var existing = arbOpportunityRepo.findByEventIdAndMarketSignature(eventId, marketSignature);
        if (existing.isPresent()) {
            log.debug("Вилка для события {} уже существует, пропускаем", eventId);
            return false;
        }

        // 10. Рассчитываем суммы ставок
        BigDecimal totalStake = new BigDecimal("100");
        BigDecimal guaranteedReturn = totalStake.divide(inverseSum, MC);
        BigDecimal profitAmount = guaranteedReturn.subtract(totalStake);

        // 11. Сохраняем вилку
        ArbOpportunitiesRecord opportunity = new ArbOpportunitiesRecord();
        opportunity.setEventId(eventId);
        opportunity.setMarketSignature(marketSignature);
        opportunity.setProfitPct(profitPct);
        opportunity.setFoundAt(OffsetDateTime.now(ZoneOffset.UTC));
        opportunity.setStatus("ACTIVE");
        opportunity.setTotalStake(totalStake);
        opportunity.setGuaranteedReturn(guaranteedReturn);
        opportunity.setProfitAmount(profitAmount);

        var saved = arbOpportunityRepo.save(opportunity);

        // Логируем найденную вилку
        log.info("!!! НАЙДЕНА ВИЛКА !!! Event ID: {}, Прибыль: {}%", eventId, profitPct);
        log.info("   Обратная сумма: {} | Гарантированный возврат: {} | Прибыль: {}",
                inverseSum, guaranteedReturn, profitAmount);

        // 12. Сохраняем ноги (лега)
        for (Map<String, Object> outcome : sortedOutcomes) {
            BigDecimal odds = (BigDecimal) outcome.get("odds");
            BigDecimal stakeShare = BigDecimal.ONE.divide(odds, MC).divide(inverseSum, MC);
            BigDecimal stakeAmount = totalStake.multiply(stakeShare, MC);

            Long bookmakerId = getLongValue(outcome.get("bookmaker_id"));
            Long marketId = getLongValue(outcome.get("market_id"));
            Long outcomeId = getLongValue(outcome.get("outcome_id"));

            if (bookmakerId == null || marketId == null || outcomeId == null) {
                log.error("Пропуск ноги: не удалось получить ID");
                continue;
            }

            ArbLegsRecord leg = new ArbLegsRecord();
            leg.setArbId(saved.getId());
            leg.setBookmakerId(bookmakerId);
            leg.setMarketId(marketId);
            leg.setOutcomeId(outcomeId);
            leg.setOdds(odds);
            leg.setStakeShare(stakeShare);
            leg.setStakeAmount(stakeAmount);

            arbLegRepo.save(leg);

            log.info("  Нога: {} ({}) - коэф {}, ставка {:.2f}, доля {:.2f}%",
                    outcome.get("outcome_key"),
                    outcome.get("bookmaker_code"),
                    odds,
                    stakeAmount,
                    stakeShare.multiply(BigDecimal.valueOf(100)));
        }

        eventPublisher.publishEvent(new ArbitrageFoundEvent(null));
        return true;
    }

    private Long getLongValue(Object obj) {
        if (obj == null) return null;
        if (obj instanceof Long) return (Long) obj;
        if (obj instanceof Integer) return ((Integer) obj).longValue();
        if (obj instanceof Number) return ((Number) obj).longValue();
        return null;
    }

    public void triggerScan() {
        scanForArbitrage();
    }

    public List<ArbOpportunityWithEventDto> getActiveArbsWithDetails() {
        // Получаем активные вилки с данными событий
        var records = dsl.select(
                        Tables.ARB_OPPORTUNITIES.ID,
                        Tables.ARB_OPPORTUNITIES.EVENT_ID,
                        Tables.ARB_OPPORTUNITIES.PROFIT_PCT,
                        Tables.ARB_OPPORTUNITIES.STATUS,
                        Tables.ARB_OPPORTUNITIES.FOUND_AT,
                        Tables.EVENTS.HOME_TEAM,
                        Tables.EVENTS.AWAY_TEAM,
                        Tables.EVENTS.EVENT_URL,
                        Tables.EVENTS.START_TIME
                )
                .from(Tables.ARB_OPPORTUNITIES)
                .join(Tables.EVENTS).on(Tables.ARB_OPPORTUNITIES.EVENT_ID.eq(Tables.EVENTS.ID))
                .where(Tables.ARB_OPPORTUNITIES.STATUS.eq("ACTIVE"))
                .orderBy(Tables.ARB_OPPORTUNITIES.PROFIT_PCT.desc())
                .fetch();

        List<ArbOpportunityWithEventDto> result = new ArrayList<>();

        for (var record : records) {
            ArbOpportunityWithEventDto dto = new ArbOpportunityWithEventDto();
            dto.setId(record.get(Tables.ARB_OPPORTUNITIES.ID));
            dto.setEventId(record.get(Tables.ARB_OPPORTUNITIES.EVENT_ID));
            dto.setProfitPct(record.get(Tables.ARB_OPPORTUNITIES.PROFIT_PCT));
            dto.setStatus(record.get(Tables.ARB_OPPORTUNITIES.STATUS));
            dto.setFoundAt(record.get(Tables.ARB_OPPORTUNITIES.FOUND_AT));
            dto.setHomeTeam(record.get(Tables.EVENTS.HOME_TEAM));
            dto.setAwayTeam(record.get(Tables.EVENTS.AWAY_TEAM));
            dto.setEventUrl(record.get(Tables.EVENTS.EVENT_URL));
            dto.setStartTime(record.get(Tables.EVENTS.START_TIME));

            // Получаем ноги вилки
            var legs = dsl.select(
                            Tables.ARB_LEGS.BOOKMAKER_ID,
                            Tables.BOOKMAKERS.CODE,
                            Tables.BOOKMAKERS.NAME,
                            Tables.OUTCOMES.OUTCOME_KEY,
                            Tables.ARB_LEGS.ODDS,
                            Tables.ARB_LEGS.STAKE_SHARE,
                            Tables.ARB_LEGS.STAKE_AMOUNT
                    )
                    .from(Tables.ARB_LEGS)
                    .join(Tables.BOOKMAKERS).on(Tables.ARB_LEGS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                    .join(Tables.OUTCOMES).on(Tables.ARB_LEGS.OUTCOME_ID.eq(Tables.OUTCOMES.ID))
                    .where(Tables.ARB_LEGS.ARB_ID.eq(dto.getId()))
                    .fetch();

            List<ArbOpportunityWithEventDto.ArbLegDto> legDtos = new ArrayList<>();
            for (var leg : legs) {
                ArbOpportunityWithEventDto.ArbLegDto legDto = new ArbOpportunityWithEventDto.ArbLegDto();
                legDto.setBookmakerId(leg.get(Tables.ARB_LEGS.BOOKMAKER_ID));
                legDto.setBookmakerCode(leg.get(Tables.BOOKMAKERS.CODE));
                legDto.setBookmakerName(leg.get(Tables.BOOKMAKERS.NAME));
                legDto.setOutcomeKey(leg.get(Tables.OUTCOMES.OUTCOME_KEY));
                legDto.setOdds(leg.get(Tables.ARB_LEGS.ODDS));
                legDto.setStakeShare(leg.get(Tables.ARB_LEGS.STAKE_SHARE));
                legDto.setStakeAmount(leg.get(Tables.ARB_LEGS.STAKE_AMOUNT));
                legDtos.add(legDto);
            }
            dto.setLegs(legDtos);
            result.add(dto);
        }

        return result;
    }

    public List<Map<String, Object>> getArbsWithDetails() {
        List<Map<String, Object>> result = new ArrayList<>();

        var arbs = dsl.selectFrom(Tables.ARB_OPPORTUNITIES)
                .where(Tables.ARB_OPPORTUNITIES.STATUS.eq("ACTIVE"))
                .orderBy(Tables.ARB_OPPORTUNITIES.FOUND_AT.desc())
                .fetch();

        for (var arb : arbs) {
            Map<String, Object> arbMap = new HashMap<>();
            arbMap.put("id", arb.getId());
            arbMap.put("eventId", arb.getEventId());
            arbMap.put("profitPct", arb.getProfitPct());
            arbMap.put("foundAt", arb.getFoundAt());
            arbMap.put("status", arb.getStatus());

            // Получаем ноги для этой вилки
            var legs = dsl.select(
                            Tables.ARB_LEGS.ODDS,
                            Tables.ARB_LEGS.STAKE_SHARE,
                            Tables.ARB_LEGS.STAKE_AMOUNT,
                            Tables.BOOKMAKERS.CODE,
                            Tables.BOOKMAKERS.NAME,
                            Tables.OUTCOMES.OUTCOME_KEY
                    )
                    .from(Tables.ARB_LEGS)
                    .join(Tables.BOOKMAKERS).on(Tables.ARB_LEGS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                    .join(Tables.OUTCOMES).on(Tables.ARB_LEGS.OUTCOME_ID.eq(Tables.OUTCOMES.ID))
                    .where(Tables.ARB_LEGS.ARB_ID.eq(arb.getId()))
                    .fetch();

            List<Map<String, Object>> legList = new ArrayList<>();
            for (var leg : legs) {
                Map<String, Object> legMap = new HashMap<>();
                legMap.put("odds", leg.get(Tables.ARB_LEGS.ODDS));
                legMap.put("stakeShare", leg.get(Tables.ARB_LEGS.STAKE_SHARE));
                legMap.put("stakeAmount", leg.get(Tables.ARB_LEGS.STAKE_AMOUNT));
                legMap.put("bookmakerCode", leg.get(Tables.BOOKMAKERS.CODE));
                legMap.put("bookmakerName", leg.get(Tables.BOOKMAKERS.NAME));
                legMap.put("outcomeKey", leg.get(Tables.OUTCOMES.OUTCOME_KEY));
                legList.add(legMap);
            }
            arbMap.put("legs", legList);
            result.add(arbMap);
        }

        return result;
    }

    // ArbFinderService.java
    public List<ArbOpportunityWithDetailsDto> getActiveArbsWithEventDetails() {
        List<ArbOpportunityWithDetailsDto> result = new ArrayList<>();

        var records = dsl.select(
                        Tables.ARB_OPPORTUNITIES.ID,
                        Tables.ARB_OPPORTUNITIES.EVENT_ID,
                        Tables.ARB_OPPORTUNITIES.PROFIT_PCT,
                        Tables.ARB_OPPORTUNITIES.STATUS,
                        Tables.ARB_OPPORTUNITIES.FOUND_AT,
                        Tables.EVENTS.HOME_TEAM,
                        Tables.EVENTS.AWAY_TEAM,
                        Tables.EVENTS.EVENT_URL,
                        Tables.EVENTS.START_TIME,
                        Tables.LEAGUES.NAME.as("league_name")
                )
                .from(Tables.ARB_OPPORTUNITIES)
                .join(Tables.EVENTS).on(Tables.ARB_OPPORTUNITIES.EVENT_ID.eq(Tables.EVENTS.ID))
                .leftJoin(Tables.LEAGUES).on(Tables.EVENTS.LEAGUE_ID.eq(Tables.LEAGUES.ID))
                .where(Tables.ARB_OPPORTUNITIES.STATUS.eq("ACTIVE"))
                .orderBy(Tables.ARB_OPPORTUNITIES.PROFIT_PCT.desc())
                .fetch();

        for (var record : records) {
            ArbOpportunityWithDetailsDto dto = new ArbOpportunityWithDetailsDto();
            dto.setId(record.get(Tables.ARB_OPPORTUNITIES.ID));
            dto.setEventId(record.get(Tables.ARB_OPPORTUNITIES.EVENT_ID));
            dto.setProfitPercentage(record.get(Tables.ARB_OPPORTUNITIES.PROFIT_PCT));
            dto.setStatus(record.get(Tables.ARB_OPPORTUNITIES.STATUS));
            dto.setFoundAt(record.get(Tables.ARB_OPPORTUNITIES.FOUND_AT));
            dto.setHomeTeam(record.get(Tables.EVENTS.HOME_TEAM));
            dto.setAwayTeam(record.get(Tables.EVENTS.AWAY_TEAM));
            dto.setEventUrl(record.get(Tables.EVENTS.EVENT_URL));
            dto.setStartTime(record.get(Tables.EVENTS.START_TIME));
            dto.setLeagueName(record.get("league_name", String.class));

            // Получаем ноги вилки
            var legs = dsl.select(
                            Tables.BOOKMAKERS.CODE,
                            Tables.BOOKMAKERS.NAME,
                            Tables.OUTCOMES.OUTCOME_KEY,
                            Tables.ARB_LEGS.ODDS,
                            Tables.ARB_LEGS.STAKE_SHARE,
                            Tables.ARB_LEGS.STAKE_AMOUNT
                    )
                    .from(Tables.ARB_LEGS)
                    .join(Tables.BOOKMAKERS).on(Tables.ARB_LEGS.BOOKMAKER_ID.eq(Tables.BOOKMAKERS.ID))
                    .join(Tables.OUTCOMES).on(Tables.ARB_LEGS.OUTCOME_ID.eq(Tables.OUTCOMES.ID))
                    .where(Tables.ARB_LEGS.ARB_ID.eq(dto.getId()))
                    .fetch();

            List<ArbOpportunityWithDetailsDto.ArbLegDetailsDto> legDtos = new ArrayList<>();
            for (var leg : legs) {
                ArbOpportunityWithDetailsDto.ArbLegDetailsDto legDto =
                        new ArbOpportunityWithDetailsDto.ArbLegDetailsDto();
                legDto.setBookmakerCode(leg.get(Tables.BOOKMAKERS.CODE));
                legDto.setBookmakerName(leg.get(Tables.BOOKMAKERS.NAME));
                legDto.setOutcomeKey(leg.get(Tables.OUTCOMES.OUTCOME_KEY));
                legDto.setOdds(leg.get(Tables.ARB_LEGS.ODDS));
                legDto.setStakeShare(leg.get(Tables.ARB_LEGS.STAKE_SHARE));
                legDto.setStakeAmount(leg.get(Tables.ARB_LEGS.STAKE_AMOUNT));
                legDtos.add(legDto);
            }
            dto.setLegs(legDtos);
            result.add(dto);
        }

        return result;
    }
}