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
import com.oddscanner.generated.tables.records.TeamsRecord;
import com.oddscanner.normalization.EventMatcher;
import com.oddscanner.normalization.LeagueMatcher;
import com.oddscanner.normalization.SportMatcher;
import com.oddscanner.normalization.TeamMatcher;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class IngestionService {
    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    @Value("${scheduler.enabled:true}")
    private boolean schedulerEnabled;

    @Value("${scheduler.fixed-delay-ms:300000}")
    private long fixedDelayMs;

    private final Map<String, BookmakerAdapter> adapters;
    private final DSLContext dsl;
    private final EventMatcher eventMatcher;
    private final TeamMatcher teamMatcher;
    private final LeagueMatcher leagueMatcher;
    private final SportMatcher sportMatcher;
    private final ApplicationEventPublisher eventPublisher;

    public IngestionService(List<BookmakerAdapter> adapterList,
                            DSLContext dsl,
                            EventMatcher eventMatcher,
                            TeamMatcher teamMatcher,
                            LeagueMatcher leagueMatcher,
                            SportMatcher sportMatcher,
                            ApplicationEventPublisher eventPublisher) {
        this.adapters = new ConcurrentHashMap<>();
        for (BookmakerAdapter adapter : adapterList) {
            this.adapters.put(adapter.code(), adapter);
            log.info("Registered BookmakerAdapter: {}", adapter.code());
        }
        this.dsl = dsl;
        this.eventMatcher = eventMatcher;
        this.teamMatcher = teamMatcher;
        this.leagueMatcher = leagueMatcher;
        this.sportMatcher = sportMatcher;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Плановый запуск загрузки данных от всех активных букмекеров
     * Интервал настраивается в application.yml (scheduler.fixed-delay-ms)
     * По умолчанию: 5 минут (300000 мс)
     */
    @Scheduled(fixedDelayString = "${scheduler.fixed-delay-ms:300000}", initialDelay = 5000) // через 5 сек, затем каждые 5 мин
    public void scheduledIngestion() {

        var allBookmakers = dsl.selectFrom(Tables.BOOKMAKERS).fetch();
        for (var bm : allBookmakers) {
            log.info("  Букмекер: code='{}', name='{}', enabled={}",
                    bm.getCode(), bm.getName(), bm.getEnabled());
        }
        log.info("  schedulerEnabled='{}'", schedulerEnabled);

        log.info("=== ДИАГНОСТИКА: Зарегистрированные адаптеры ===");
        for (String key : adapters.keySet()) {
            log.info("  Адаптер: code='{}', class={}", key, adapters.get(key).getClass().getSimpleName());
        }


        if (!schedulerEnabled) {
            log.debug("Планировщик отключен в конфигурации");
            return;
        }

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║         ПЛАНОВЫЙ ЗАПУСК ЗАГРУЗКИ ДАННЫХ                      ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("Интервал: {} минут", fixedDelayMs / 60000);

        // Получаем список активных букмекеров
        var activeBookmakers = dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.ENABLED.isTrue())
                .fetch();

        log.info("📊 Найдено активных букмекеров: {}", activeBookmakers.size());

        for (var bookmaker : activeBookmakers) {
            String code = bookmaker.getCode();
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("🔄 Запуск загрузки для: {} ({})", code, bookmaker.getName());
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            try {
                long startTime = System.currentTimeMillis();
                ingest(code);
                long duration = System.currentTimeMillis() - startTime;
                log.info("✅ Загрузка {} завершена за {} секунд", code, duration / 1000);
            } catch (Exception e) {
                log.error("❌ Ошибка при загрузке {}: {}", code, e.getMessage(), e);
            }
        }

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║         ПЛАНОВЫЙ ЗАПУСК ЗАВЕРШЕН                             ║");
        log.info("╚══════════════════════════════════════════════════════════════╝");
        log.info("⏰ Следующий запуск через {} минут", fixedDelayMs / 60000);
    }

    public void ingest(String bookmakerCode) {
        log.info(">>> INGEST CALLED for bookmaker: {}", bookmakerCode);  //
        log.info("Starting ingestion for bookmaker: {}", bookmakerCode);

        // Проверяем, активен ли букмекер в БД
        var bookmaker = dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOne();

        if (bookmaker == null) {
            log.error("Bookmaker not found in database for code: {}", bookmakerCode);
            return;
        }

        if (!bookmaker.getEnabled()) {
            log.warn("⏭️ Bookmaker {} ({}) is DISABLED in database. Skipping ingestion.",
                    bookmakerCode, bookmaker.getName());
            return;
        }

        log.info("✅ Bookmaker {} ({}) is ENABLED. Proceeding with ingestion.",
                bookmakerCode, bookmaker.getName());

        BookmakerAdapter adapter = adapters.get(bookmakerCode);
        log.info("Adapter for {}: {}", bookmakerCode, adapter);  // <-- ДОБАВИТЬ
        if (adapter == null) {
            log.error("No adapter found for bookmaker code: {}", bookmakerCode);
            return;
        }

        try {
            List<RawEvent> rawEvents = adapter.fetchEvents();
            log.info("Получено {} событий от адаптера {}", rawEvents.size(), bookmakerCode);

            if (rawEvents.isEmpty()) {
                log.info("No events received from adapter for {}", bookmakerCode);
                return;
            }

            log.info("Received {} events from adapter for {}", rawEvents.size(), bookmakerCode);

            for (RawEvent rawEvent : rawEvents) {
                log.debug("Processing event: {} vs {} (external ID: {}) from {}",
                        rawEvent.getHomeTeamName(),
                        rawEvent.getAwayTeamName(),
                        rawEvent.getExternalId(),
                        bookmakerCode);

                Long internalEventId = findOrCreateInternalEvent(rawEvent, bookmakerCode);

                if (internalEventId == null) {
                    log.warn("Could not find or create internal event for external ID: {}. Skipping markets.", rawEvent.getExternalId());
                    continue;
                }

                List<RawMarket> rawMarkets = adapter.fetchMarkets(rawEvent.getExternalId());
                log.debug("Processing {} markets for event {} from {}", rawMarkets.size(), rawEvent.getExternalId(), bookmakerCode);

                for (RawMarket rawMarket : rawMarkets) {
                    MarketsRecord marketRecord = new MarketsRecord();
                    marketRecord.setEventId(internalEventId);

                    Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                            .from(Tables.BOOKMAKERS)
                            .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                            .fetchOneInto(Long.class);

                    if (bookmakerId == null) {
                        log.error("Bookmaker ID not found for code: {}", bookmakerCode);
                        continue;
                    }
                    marketRecord.setBookmakerId(bookmakerId);

                    marketRecord.setMarketType(rawMarket.getMarketTypeName());
                    marketRecord.setPeriod(rawMarket.getPeriodName());
                    marketRecord.setLine(rawMarket.getLine());
                    marketRecord.setSourceExternalId(rawMarket.getExternalId());

                    MarketsRecord savedMarket = dsl.insertInto(Tables.MARKETS)
                            .set(marketRecord)
                            .onConflict(Tables.MARKETS.EVENT_ID, Tables.MARKETS.BOOKMAKER_ID,
                                    Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.PERIOD, Tables.MARKETS.LINE)
                            .doUpdate()
                            .set(Tables.MARKETS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .set(Tables.MARKETS.SOURCE_EXTERNAL_ID, rawMarket.getExternalId())
                            .returning(Tables.MARKETS.ID)
                            .fetchOne();

                    Long marketInternalId = savedMarket.getId();

                    if (rawMarket.getOutcomes() != null) {
                        for (RawOutcome rawOutcome : rawMarket.getOutcomes()) {
                            OutcomesRecord outcomeRecord = new OutcomesRecord();
                            outcomeRecord.setMarketId(marketInternalId);
                            outcomeRecord.setOutcomeKey(rawOutcome.getOutcomeKeyName());
                            outcomeRecord.setValue(rawOutcome.getOutcomeValueDescription());
                            outcomeRecord.setOdds(rawOutcome.getOdds());
                            outcomeRecord.setIsActive(rawOutcome.isActive());

                            dsl.insertInto(Tables.OUTCOMES)
                                    .set(outcomeRecord)
                                    .onConflict(Tables.OUTCOMES.MARKET_ID, Tables.OUTCOMES.OUTCOME_KEY)
                                    .doUpdate()
                                    .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                                    .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                                    .set(Tables.OUTCOMES.VALUE, rawOutcome.getOutcomeValueDescription())
                                    .set(Tables.OUTCOMES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                                    .execute();
                        }
                    }
                }

                OddsUpdatedEvent event = new OddsUpdatedEvent(bookmakerCode, rawEvent.getExternalId());
                eventPublisher.publishEvent(event);
                log.debug("Published OddsUpdatedEvent for bookmaker {}, event {}", bookmakerCode, rawEvent.getExternalId());
            }

            log.info("Completed ingestion for bookmaker: {}", bookmakerCode);

        } catch (Exception e) {
            log.error("Error during ingestion for bookmaker: {}", bookmakerCode, e);
        }
    }

    private Long findOrCreateInternalEvent(RawEvent rawEvent, String bookmakerCode) {
        // Проверяем, не слишком ли старое событие (например, старше 2 часов)
        LocalDateTime now = LocalDateTime.now();
        if (rawEvent.getStartTime().isBefore(now.minusHours(2))) {
            log.debug("Skipping old event: {} vs {} (start time: {})",
                    rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getStartTime());
            return null;
        }

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
            }
        }

        // Пытаемся найти существующее событие по командам и времени (в пределах 1 часа)
        Long sportId = 1L; // football по умолчанию

        Long homeTeamId = findOrCreateTeam(rawEvent.getHomeTeamName(), sportId);
        Long awayTeamId = findOrCreateTeam(rawEvent.getAwayTeamName(), sportId);

        if (homeTeamId == null || awayTeamId == null) {
            log.warn("Could not find or create teams for event: {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        // Ищем существующее событие с теми же командами и временем в пределах 1 часа
        EventsRecord existingEvent = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                .and(Tables.EVENTS.START_TIME.between(
                        rawEvent.getStartTime().minusHours(1).atOffset(ZoneOffset.UTC),
                        rawEvent.getStartTime().plusHours(1).atOffset(ZoneOffset.UTC)
                ))
                .fetchOneInto(EventsRecord.class);

        if (existingEvent != null) {
            log.debug("Found existing event by teams: {} vs {} (ID: {})",
                    rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), existingEvent.getId());
            return existingEvent.getId();
        }

        // Создаём новое событие только если оно не слишком старое и не найдено существующее
        if (rawEvent.getStartTime().isBefore(now.minusHours(1))) {
            log.debug("Not creating new event for old match: {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        // Создаём новое событие
        EventsRecord eventRecord = new EventsRecord();
        eventRecord.setHomeTeamId(homeTeamId);
        eventRecord.setAwayTeamId(awayTeamId);
        eventRecord.setStartTime(rawEvent.getStartTime().atOffset(ZoneOffset.UTC));
        eventRecord.setStatus("SCHEDULED");
        eventRecord.setEventUrl(rawEvent.getEventUrl());
        eventRecord.setBookmakerCode(bookmakerCode);  // <-- ВОТ ЭТА СТРОЧКА БЫЛА ОТСУТСТВОВАЛА!

        EventsRecord savedEvent = dsl.insertInto(Tables.EVENTS)
                .set(eventRecord)
                .returning(Tables.EVENTS.ID)
                .fetchOne();

        if (savedEvent == null) {
            log.error("Failed to create new event for {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        Long internalEventId = savedEvent.getId();
        log.debug("Created new event ID: {} for {} vs {} (bookmaker: {})",
                internalEventId, rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), bookmakerCode);

        // Сохраняем внешнюю ссылку
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
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, rawEvent.getStartTime().atOffset(ZoneOffset.UTC))
                    .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                    .doUpdate()
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, internalEventId)
                    .execute();
        }

        return internalEventId;
    }


    private Long findOrCreateTeam(String teamName, Long sportId) {
        if (teamName == null || teamName.isEmpty() || "TBD".equals(teamName)) {
            return null;
        }

        // Ищем существующую команду
        TeamsRecord existingTeam = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(teamName))
                .fetchOneInto(TeamsRecord.class);

        if (existingTeam != null) {
            return existingTeam.getId();
        }

        // Создаём новую команду
        TeamsRecord newTeam = new TeamsRecord();
        newTeam.setCanonicalName(teamName);
        newTeam.setSportId(sportId);
        newTeam.setNormalizedName(teamName.toLowerCase());

        TeamsRecord savedTeam = dsl.insertInto(Tables.TEAMS)
                .set(newTeam)
                .returning(Tables.TEAMS.ID)
                .fetchOne();

        return savedTeam != null ? savedTeam.getId() : null;
    }

    /**
     * Возвращает статус всех букмекеров
     */
    public void printBookmakersStatus() {
        var bookmakers = dsl.select(
                        Tables.BOOKMAKERS.ID,
                        Tables.BOOKMAKERS.CODE,
                        Tables.BOOKMAKERS.NAME,
                        Tables.BOOKMAKERS.ENABLED
                )
                .from(Tables.BOOKMAKERS)
                .fetch();

        // Собираем активных букмекеров
        var activeBookmakers = bookmakers.stream()
                .filter(bm -> bm.get(Tables.BOOKMAKERS.ENABLED))
                .toList();

        // Формируем строку с названиями активных букмекеров
        String activeNames = activeBookmakers.stream()
                .map(bm -> bm.get(Tables.BOOKMAKERS.NAME))
                .collect(Collectors.joining(", "));

        log.info("=== СТАТУС БУКМЕКЕРОВ ===");
        log.info("📊 Найдено активных букмекеров: {} ({})", activeBookmakers.size(), activeNames);

        for (var bm : bookmakers) {
            String status = bm.get(Tables.BOOKMAKERS.ENABLED) ? "✅ АКТИВЕН" : "❌ НЕ АКТИВЕН";
            String registered = adapters.containsKey(bm.get(Tables.BOOKMAKERS.CODE)) ? " (зарегистрирован)" : " (НЕ зарегистрирован)";
            log.info("- ID: {}, Code: {}, Name: {} - {}{}",
                    bm.get(Tables.BOOKMAKERS.ID),
                    bm.get(Tables.BOOKMAKERS.CODE),
                    bm.get(Tables.BOOKMAKERS.NAME),
                    status,
                    registered);
        }
        log.info("=======================");
    }
}