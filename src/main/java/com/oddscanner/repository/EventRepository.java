package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.BookmakersRecord;
import com.oddscanner.generated.tables.records.EventsRecord;
import com.oddscanner.generated.tables.records.MarketsRecord;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.parser.RawEvent;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@Repository
public class EventRepository {
    private static final Logger log = LoggerFactory.getLogger(EventRepository.class);
    private final DSLContext dsl;

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
        for (RawEvent event : events) {
            try {
                Long eventId = saveEvent(bookmakerId, event);
                savedCount++;

                for (RawEvent.RawMarket market : event.markets()) {
                    Long marketId = saveMarket(eventId, market);
                    saveOutcomes(marketId, market.outcomes());
                }
            } catch (Exception e) {
                log.error("[EventRepository] Ошибка сохранения события {}: {}",
                        event.externalId(), e.getMessage(), e);
            }
        }

        log.info("[EventRepository] Успешно сохранено {} событий из {}", savedCount, events.size());
    }

    private Long getBookmakerId(String code) {
        return dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(code))
                .fetchOne(Tables.BOOKMAKERS.ID);
    }

    private Long saveEvent(Long bookmakerId, RawEvent event) {
        return dsl.insertInto(Tables.EVENTS)
                .set(Tables.EVENTS.BOOKMAKER_ID, bookmakerId)
                .set(Tables.EVENTS.EXTERNAL_ID, event.externalId())
                .set(Tables.EVENTS.LEAGUE, event.leagueName())
                .set(Tables.EVENTS.HOME_TEAM, event.team1())
                .set(Tables.EVENTS.AWAY_TEAM, event.team2())
                .set(Tables.EVENTS.START_TIME, event.startsAt())
                .set(Tables.EVENTS.STATUS, "SCHEDULED")
                .set(Tables.EVENTS.EVENT_URL, event.eventUrl())
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .onConflict(Tables.EVENTS.BOOKMAKER_ID, Tables.EVENTS.EXTERNAL_ID)
                .doUpdate()
                .set(Tables.EVENTS.LEAGUE, event.leagueName())
                .set(Tables.EVENTS.HOME_TEAM, event.team1())
                .set(Tables.EVENTS.AWAY_TEAM, event.team2())
                .set(Tables.EVENTS.START_TIME, event.startsAt())
                .set(Tables.EVENTS.STATUS, "SCHEDULED")
                .set(Tables.EVENTS.EVENT_URL, event.eventUrl())
                .set(Tables.EVENTS.UPDATED_AT, LocalDateTime.now())
                .returning(Tables.EVENTS.ID)
                .fetchOne()
                .getId();
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
        // Сначала проверяем, существует ли рынок
        Long existingId = dsl.select(Tables.MARKETS.ID)
                .from(Tables.MARKETS)
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .and(Tables.MARKETS.MARKET_TYPE.eq(market.marketType()))
                .fetchOne(Tables.MARKETS.ID);

        if (existingId != null) {
            // Обновляем существующий
            dsl.update(Tables.MARKETS)
                    .set(Tables.MARKETS.MARKET_NAME, market.marketType())
                    .where(Tables.MARKETS.ID.eq(existingId))
                    .execute();
            return existingId;
        } else {
            // Вставляем новый
            return dsl.insertInto(Tables.MARKETS)
                    .set(Tables.MARKETS.EVENT_ID, eventId)
                    .set(Tables.MARKETS.MARKET_TYPE, market.marketType())
                    .set(Tables.MARKETS.MARKET_NAME, market.marketType())
                    .returning(Tables.MARKETS.ID)
                    .fetchOne()
                    .getId();
        }
    }

    private void saveOutcomes(Long marketId, List<RawEvent.RawOutcome> outcomes) {
        // Удаляем старые исходы для этого рынка
        dsl.deleteFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.MARKET_ID.eq(marketId))
                .execute();

        // Вставляем новые
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
}