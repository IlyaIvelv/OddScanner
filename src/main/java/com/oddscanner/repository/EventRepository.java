package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.parser.RawEvent;
import org.jooq.DSLContext;
import org.jooq.InsertValuesStep8;
import org.jooq.InsertValuesStep5;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Repository
public class EventRepository {
    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private final DSLContext dsl;
    private static final int BATCH_SIZE = 500;

    public EventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Transactional
    public void saveEvents(String bookmakerCode, List<RawEvent> events) {
        if (events.isEmpty()) return;

        Long bookmakerId = getBookmakerId(bookmakerCode);
        log.info("[EventRepository] bookmakerCode={}, bookmakerId={}, eventsCount={}",
                bookmakerCode, bookmakerId, events.size());

        if (bookmakerId == null) {
            log.error("[EventRepository] Букмекер с кодом {} не найден!", bookmakerCode);
            return;
        }

        int savedCount = 0;
        for (int i = 0; i < events.size(); i += BATCH_SIZE) {
            List<RawEvent> batch = events.subList(i, Math.min(i + BATCH_SIZE, events.size()));
            savedCount += saveEventsBatch(bookmakerId, batch);
        }

        log.info("[EventRepository] Успешно сохранено {} событий из {}", savedCount, events.size());
    }

    private int saveEventsBatch(Long bookmakerId, List<RawEvent> events) {
        upsertEventsBatch(bookmakerId, events);
        Map<String, Long> eventIds = fetchEventIds(bookmakerId, events);

        int savedCount = 0;
        for (RawEvent event : events) {
            Long eventId = eventIds.get(event.externalId());
            if (eventId == null) {
                log.error("[EventRepository] Не найден ID для события {}", event.externalId());
                continue;
            }

            try {
                for (RawEvent.RawMarket market : event.markets()) {
                    Long marketId = saveMarket(eventId, market);
                    saveOutcomes(marketId, market.outcomes());
                }
                savedCount++;
            } catch (Exception e) {
                log.error("[EventRepository] Ошибка сохранения события {}: {}",
                        event.externalId(), e.getMessage(), e);
            }
        }
        return savedCount;
    }

    private void upsertEventsBatch(Long bookmakerId, List<RawEvent> events) {
        InsertValuesStep8 insertStep = dsl.insertInto(
                Tables.EVENTS,
                Tables.EVENTS.BOOKMAKER_ID,
                Tables.EVENTS.EXTERNAL_ID,
                Tables.EVENTS.LEAGUE,
                Tables.EVENTS.HOME_TEAM,
                Tables.EVENTS.AWAY_TEAM,
                Tables.EVENTS.START_TIME,
                Tables.EVENTS.STATUS,
                Tables.EVENTS.EVENT_URL
        );

        for (RawEvent event : events) {
            insertStep = insertStep.values(
                    bookmakerId,
                    event.externalId(),
                    event.leagueName(),
                    event.team1(),
                    event.team2(),
                    event.startsAt(),
                    "SCHEDULED",
                    event.eventUrl()
            );
        }

        insertStep
                .onConflict(Tables.EVENTS.BOOKMAKER_ID, Tables.EVENTS.EXTERNAL_ID)
                .doUpdate()
                .set(Tables.EVENTS.LEAGUE, org.jooq.impl.DSL.excluded(Tables.EVENTS.LEAGUE))
                .set(Tables.EVENTS.HOME_TEAM, org.jooq.impl.DSL.excluded(Tables.EVENTS.HOME_TEAM))
                .set(Tables.EVENTS.AWAY_TEAM, org.jooq.impl.DSL.excluded(Tables.EVENTS.AWAY_TEAM))
                .set(Tables.EVENTS.START_TIME, org.jooq.impl.DSL.excluded(Tables.EVENTS.START_TIME))
                .set(Tables.EVENTS.STATUS, "SCHEDULED")
                .set(Tables.EVENTS.EVENT_URL, org.jooq.impl.DSL.excluded(Tables.EVENTS.EVENT_URL))
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .execute();
    }

    private Map<String, Long> fetchEventIds(Long bookmakerId, List<RawEvent> events) {
        List<String> externalIds = events.stream()
                .map(RawEvent::externalId)
                .toList();

        return dsl.select(Tables.EVENTS.EXTERNAL_ID, Tables.EVENTS.ID)
                .from(Tables.EVENTS)
                .where(Tables.EVENTS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENTS.EXTERNAL_ID.in(externalIds))
                .fetchMap(Tables.EVENTS.EXTERNAL_ID, Tables.EVENTS.ID);
    }

    private Long getBookmakerId(String code) {
        return dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(org.jooq.impl.DSL.upper(Tables.BOOKMAKERS.CODE).eq(code.toUpperCase()))
                .fetchOne(Tables.BOOKMAKERS.ID);
    }

    public void markInactiveEvents(String bookmakerCode, Set<String> activeExternalIds) {
        Long bookmakerId = getBookmakerId(bookmakerCode);

        dsl.update(Tables.EVENTS)
                .set(Tables.EVENTS.STATUS, "INACTIVE")
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .where(Tables.EVENTS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENTS.EXTERNAL_ID.notIn(activeExternalIds))
                .and(Tables.EVENTS.STATUS.ne("INACTIVE"))
                .execute();
    }

    private Long saveMarket(Long eventId, RawEvent.RawMarket market) {
        var insertStep = dsl.insertInto(
                Tables.MARKETS,
                Tables.MARKETS.EVENT_ID,
                Tables.MARKETS.MARKET_TYPE,
                Tables.MARKETS.MARKET_NAME
        );

        return insertStep
                .values(eventId, market.marketType(), market.marketType())
                .onConflict(Tables.MARKETS.EVENT_ID, Tables.MARKETS.MARKET_TYPE)
                .doUpdate()
                .set(Tables.MARKETS.MARKET_NAME, org.jooq.impl.DSL.excluded(Tables.MARKETS.MARKET_NAME))
                .returning(Tables.MARKETS.ID)
                .fetchOne()
                .get(Tables.MARKETS.ID);
    }

    private void saveOutcomes(Long marketId, List<RawEvent.RawOutcome> outcomes) {
        dsl.deleteFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.MARKET_ID.eq(marketId))
                .execute();

        List<OutcomesRecord> records = new ArrayList<>();
        for (RawEvent.RawOutcome outcome : outcomes) {
            var record = new OutcomesRecord();
            record.setMarketId(marketId);
            record.setOutcomeName(outcome.name());
            record.setOdds(outcome.odds());
            record.setIsActive(true);
            records.add(record);
        }

        if (!records.isEmpty()) {
            dsl.batchInsert(records).execute();
        }
    }

    public boolean isBookmakerActive(String code) {
        log.info("[EventRepository] Проверяю активность для code='{}'", code);

        Boolean isActive = dsl.select(Tables.BOOKMAKERS.IS_ACTIVE)
                .from(Tables.BOOKMAKERS)
                .where(org.jooq.impl.DSL.upper(Tables.BOOKMAKERS.CODE).eq(code.toUpperCase()))
                .fetchOne(Tables.BOOKMAKERS.IS_ACTIVE);

        log.info("[EventRepository] Результат для '{}': isActive={}", code, isActive);
        return isActive != null && isActive;
    }

}