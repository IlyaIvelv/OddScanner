// File: src/main/java/com/oddscanner/ingestion/IngestionService.java

package com.oddscanner.ingestion;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import com.oddscanner.generated.tables.records.EventExternalRefsRecord;
import com.oddscanner.generated.tables.records.MarketsRecord;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.normalization.EventMatcher;
import com.oddscanner.normalization.LeagueMatcher;
import com.oddscanner.normalization.SportMatcher;
import com.oddscanner.normalization.TeamMatcher;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    private final Map<String, BookmakerAdapter> adapters;
    private final DSLContext dsl;
    private final EventMatcher eventMatcher; // <-- Добавлен
    private final TeamMatcher teamMatcher; // <-- Добавлен
    private final LeagueMatcher leagueMatcher; // <-- Добавлен
    private final SportMatcher sportMatcher; // <-- Добавлен
    private final ApplicationEventPublisher eventPublisher;

    public IngestionService(List<BookmakerAdapter> adapterList,
                            DSLContext dsl,
                            EventMatcher eventMatcher, // <-- Внедряем
                            TeamMatcher teamMatcher, // <-- Внедряем
                            LeagueMatcher leagueMatcher, // <-- Внедряем
                            SportMatcher sportMatcher, // <-- Внедряем
                            ApplicationEventPublisher eventPublisher) {
        this.adapters = new ConcurrentHashMap<>();
        for (BookmakerAdapter adapter : adapterList) {
            this.adapters.put(adapter.code(), adapter);
            log.info("Registered BookmakerAdapter: {}", adapter.code());
        }
        this.dsl = dsl;
        this.eventMatcher = eventMatcher; // <-- Сохраняем
        this.teamMatcher = teamMatcher; // <-- Сохраняем
        this.leagueMatcher = leagueMatcher; // <-- Сохраняем
        this.sportMatcher = sportMatcher; // <-- Сохраняем
        this.eventPublisher = eventPublisher;
    }

    public void ingest(String bookmakerCode) {
        log.info("Starting ingestion for bookmaker: {}", bookmakerCode);

        BookmakerAdapter adapter = adapters.get(bookmakerCode);
        if (adapter == null) {
            log.error("No adapter found for bookmaker code: {}", bookmakerCode);
            return;
        }

        try {
            List<RawEvent> rawEvents = adapter.fetchEvents();

            if (rawEvents.isEmpty()) {
                log.info("No events received from adapter for {}", bookmakerCode);
                return;
            }

            log.info("Received {} events from adapter for {}", rawEvents.size(), bookmakerCode);

            for (RawEvent rawEvent : rawEvents) {
                // ИСПРАВЛЕНО: Используем доступные поля для описания события
                log.debug("Processing event: {} vs {} (external ID: {}) from {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getExternalId(), bookmakerCode);

                // --- ШАГ 1: Найти или создать внутреннее событие через нормализацию ---
                // Используем eventMatcher для нормализации и сопоставления
                // (псевдокод, см. комментарий в JooqEventMatcher)
                // EventsRecord canonicalEvent = eventMatcher.findOrCreateEventByRawData(
                //     rawEvent.getHomeTeamName(),
                //     rawEvent.getAwayTeamName(),
                //     rawEvent.getLeagueName(),
                //     rawEvent.getSportName(), // Предполагаем, что добавлено в RawEvent
                //     rawEvent.getStartTime(),
                //     bookmakerCode
                // );
                // Long internalEventId = canonicalEvent.getId();

                // --- ВРЕМЕННОЕ РЕШЕНИЕ: Используем старый метод findOrCreateInternalEvent ---
                // Это будет заменено на вызов eventMatcher.findOrCreateEventByRawData(...)
                Long internalEventId = findOrCreateInternalEvent(rawEvent, bookmakerCode);

                if (internalEventId == null) {
                    log.warn("Could not find or create internal event for external ID: {}. Skipping markets.", rawEvent.getExternalId());
                    continue;
                }

                // --- ШАГ 2: Получить и сохранить рынки для этого события ---
                List<RawMarket> rawMarkets = adapter.fetchMarkets(rawEvent.getExternalId());
                log.debug("Processing {} markets for event {} from {}", rawMarkets.size(), rawEvent.getExternalId(), bookmakerCode);

                for (RawMarket rawMarket : rawMarkets) {
                    // Сохраняем рынок
                    MarketsRecord marketRecord = new MarketsRecord();
                    marketRecord.setEventId(internalEventId);

                    // Получаем bookmaker_id по code
                    Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                            .from(Tables.BOOKMAKERS)
                            .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                            .fetchOneInto(Long.class);

                    if (bookmakerId == null) {
                        log.error("Bookmaker ID not found for code: {}", bookmakerCode);
                        continue; // Пропускаем рынок
                    }
                    marketRecord.setBookmakerId(bookmakerId);

                    marketRecord.setMarketType(rawMarket.getMarketTypeName());
                    marketRecord.setPeriod(rawMarket.getPeriodName());
                    marketRecord.setLine(rawMarket.getLine());
                    // source_external_id - используем внешний ID рынка
                    marketRecord.setSourceExternalId(rawMarket.getExternalId());

                    MarketsRecord savedMarket = dsl.insertInto(Tables.MARKETS)
                            .set(marketRecord)
                            .onConflict(Tables.MARKETS.EVENT_ID, Tables.MARKETS.BOOKMAKER_ID,
                                    Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.PERIOD, Tables.MARKETS.LINE)
                            .doUpdate()
                            // ИСПРАВЛЕНО: Используем OffsetDateTime
                            .set(Tables.MARKETS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .set(Tables.MARKETS.SOURCE_EXTERNAL_ID, rawMarket.getExternalId()) // Обновим внешний ID
                            .returning(Tables.MARKETS.ID)
                            .fetchOne();

                    Long marketInternalId = savedMarket.getId();

                    // --- ШАГ 3: Сохранить исходы для этого рынка ---
                    if (rawMarket.getOutcomes() != null) {
                        for (RawOutcome rawOutcome : rawMarket.getOutcomes()) {
                            OutcomesRecord outcomeRecord = new OutcomesRecord();
                            outcomeRecord.setMarketId(marketInternalId);
                            outcomeRecord.setOutcomeKey(rawOutcome.getOutcomeKeyName());
                            outcomeRecord.setValue(rawOutcome.getOutcomeValueDescription()); // Используем поле value
                            outcomeRecord.setOdds(rawOutcome.getOdds());
                            outcomeRecord.setIsActive(rawOutcome.isActive());

                            // Сохраняем исход, обновляя при конфликте
                            dsl.insertInto(Tables.OUTCOMES)
                                    .set(outcomeRecord)
                                    .onConflict(Tables.OUTCOMES.MARKET_ID, Tables.OUTCOMES.OUTCOME_KEY)
                                    .doUpdate()
                                    .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                                    .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                                    .set(Tables.OUTCOMES.VALUE, rawOutcome.getOutcomeValueDescription())
                                    // ИСПРАВЛЕНО: Используем OffsetDateTime
                                    .set(Tables.OUTCOMES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                                    .execute();
                        }
                    }
                }

                // --- ШАГ 4: Опубликовать событие ---
                OddsUpdatedEvent event = new OddsUpdatedEvent(bookmakerCode, rawEvent.getExternalId());
                eventPublisher.publishEvent(event);
                log.debug("Published OddsUpdatedEvent for bookmaker {}, event {}", bookmakerCode, rawEvent.getExternalId());
            }

            log.info("Completed ingestion for bookmaker: {}", bookmakerCode);

        } catch (Exception e) {
            log.error("Error during ingestion for bookmaker: {}", bookmakerCode, e);
        }

    }

    // Метод для поиска или создания внутреннего ID события (временно, до интеграции с EventMatcher)
    private Long findOrCreateInternalEvent(RawEvent rawEvent, String bookmakerCode) {
        // 1. Попробовать найти сопоставление по внешнему ID и букмекеру
        Optional<EventExternalRefsRecord> existingRefOpt = dsl.selectFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.eq(
                        dsl.select(Tables.BOOKMAKERS.ID).from(Tables.BOOKMAKERS).where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode)).limit(1)
                ))
                .and(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID.eq(rawEvent.getExternalId()))
                .fetchOptional();

        if (existingRefOpt.isPresent()) {
            Long eventId = existingRefOpt.get().getEventId();
            if (eventId != null) {
                log.debug("Found existing internal event ID: {} for external ID: {}", eventId, rawEvent.getExternalId());
                return eventId;
            } else {
                log.warn("Found external ref for {} but internal event_id is NULL. This is inconsistent.", rawEvent.getExternalId());
            }
        }

        // 2. Если не найдено, нужно создать новое событие и сопоставление
        // Это требует нормализации команд, лиги, спорта -> см. следующий этап (Normalization).
        // Пока создадим "каноническое" событие на основе *сырых* данных.
        // Это НЕ ПРАВИЛЬНО в долгосрочной перспективе, но работает как заглушка.

        // ПЛОХО: Прямое сопоставление по именам. Нужна нормализация!
        // Попробуем найти существующую лигу по названию и спорту.
        // Попробуем найти существующую команду по названию.
        // Это может привести к дубликатам или неправильным сопоставлениям.

        // Пример грубого поиска (плохо):
        Long sportId = dsl.select(Tables.SPORTS.ID)
                .from(Tables.SPORTS)
                .where(Tables.SPORTS.CODE.eq("unknown")) // ПЛОХО: нужно нормализовать rawEvent.getSportName()
                .limit(1)
                .fetchOneInto(Long.class);

        Long leagueId = dsl.select(Tables.LEAGUES.ID)
                .from(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.eq(rawEvent.getLeagueName())) // ПЛОХО: нужно нормализовать
                .and(Tables.LEAGUES.SPORT_ID.eq(sportId))
                .limit(1)
                .fetchOneInto(Long.class);

        // ПЛОХО: Поиск команд по точному совпадению имён
        Long homeTeamId = dsl.select(Tables.TEAMS.ID)
                .from(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(rawEvent.getHomeTeamName())) // ПЛОХО: нужно нормализовать
                .limit(1)
                .fetchOneInto(Long.class);

        Long awayTeamId = dsl.select(Tables.TEAMS.ID)
                .from(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(rawEvent.getAwayTeamName())) // ПЛОХО: нужно нормализовать
                .limit(1)
                .fetchOneInto(Long.class);

        // Если команды не найдены, можно попробовать fuzzy search или создать новые.
        // Пока что, если не найдены, создадим их как "канонические" с теми же именами.
        if (homeTeamId == null) {
            log.debug("Creating new home team: {}", rawEvent.getHomeTeamName());
            homeTeamId = dsl.insertInto(Tables.TEAMS)
                    .set(Tables.TEAMS.CANONICAL_NAME, rawEvent.getHomeTeamName())
                    .set(Tables.TEAMS.SPORT_ID, sportId) // Может быть null
                    .set(Tables.TEAMS.NORMALIZED_NAME, rawEvent.getHomeTeamName().toLowerCase()) // ПЛОХО: грубая нормализация
                    .returning(Tables.TEAMS.ID)
                    .fetchOne()
                    .getId();
        }

        if (awayTeamId == null) {
            log.debug("Creating new away team: {}", rawEvent.getAwayTeamName());
            awayTeamId = dsl.insertInto(Tables.TEAMS)
                    .set(Tables.TEAMS.CANONICAL_NAME, rawEvent.getAwayTeamName())
                    .set(Tables.TEAMS.SPORT_ID, sportId) // Может быть null
                    .set(Tables.TEAMS.NORMALIZED_NAME, rawEvent.getAwayTeamName().toLowerCase()) // ПЛОХО: грубая нормализация
                    .returning(Tables.TEAMS.ID)
                    .fetchOne()
                    .getId();
        }

        // Создаём событие
        EventsRecord eventRecord = new EventsRecord();
        eventRecord.setLeagueId(leagueId); // Может быть null
        eventRecord.setHomeTeamId(homeTeamId);
        eventRecord.setAwayTeamId(awayTeamId);
        // ИСПРАВЛЕНО: Преобразуем LocalDateTime в OffsetDateTime
        eventRecord.setStartTime(rawEvent.getStartTime().atOffset(ZoneOffset.UTC));
        eventRecord.setStatus("SCHEDULED"); // Установим статус по умолчанию
        // dedupe_key можно сформировать из home_team_id, away_team_id, start_time
        // eventRecord.setDedupeKey(homeTeamId + "_" + awayTeamId + "_" + rawEvent.getStartTime().toString());

        EventsRecord savedEvent = dsl.insertInto(Tables.EVENTS)
                .set(eventRecord)
                .onConflict(Tables.EVENTS.HOME_TEAM_ID, Tables.EVENTS.AWAY_TEAM_ID, Tables.EVENTS.START_TIME)
                .doNothing() // Или doUpdate, если нужно обновлять статус
                .returning(Tables.EVENTS.ID)
                .fetchOne();

        if (savedEvent == null) {
            // Конфликт и doNothing => событие уже существует
            savedEvent = dsl.selectFrom(Tables.EVENTS)
                    .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                    .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                    // ИСПРАВЛЕНО: Преобразуем LocalDateTime в OffsetDateTime
                    .and(Tables.EVENTS.START_TIME.eq(rawEvent.getStartTime().atOffset(ZoneOffset.UTC)))
                    .limit(1)
                    .fetchOne();
        }

        Long internalEventId = savedEvent.getId();
        log.debug("Using internal event ID: {} for external ID: {}", internalEventId, rawEvent.getExternalId());

        // 3. Создать сопоставление во внешней таблице
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .limit(1)
                .fetchOneInto(Long.class);

        if (bookmakerId != null) {
            dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, internalEventId)
                    .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
                    .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                    // ИСПРАВЛЕНО: Преобразуем LocalDateTime в OffsetDateTime
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, rawEvent.getStartTime().atOffset(ZoneOffset.UTC))
                    .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                    .doUpdate()
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, internalEventId) // Обновим ID, если был NULL
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                    // ИСПРАВЛЕНО: Преобразуем LocalDateTime в OffsetDateTime
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, rawEvent.getStartTime().atOffset(ZoneOffset.UTC))
                    // ИСПРАВЛЕНО: Используем OffsetDateTime
                    .set(Tables.EVENT_EXTERNAL_REFS.LAST_SEEN_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .execute();
        } else {
            log.error("Bookmaker ID not found for code: {}, cannot create event_external_ref for event {}", bookmakerCode, rawEvent.getExternalId());
        }

        return internalEventId;
    }
}