package com.oddscanner.ingestion;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import com.oddscanner.generated.tables.records.EventExternalRefsRecord;
import com.oddscanner.generated.tables.records.LeaguesRecord;
import com.oddscanner.generated.tables.records.MarketsRecord;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import com.oddscanner.generated.tables.records.TeamsRecord;
import com.oddscanner.normalization.EventMatcher;
import com.oddscanner.normalization.LeagueMatcher;
import com.oddscanner.normalization.SportMatcher;
import com.oddscanner.normalization.TeamMatcher;
import com.oddscanner.scanner.EventMatcherService;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.time.Duration;

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
                            TeamMatcher teamMatcher,  // ← ДОЛЖЕН БЫТЬ
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
        log.info(">>> INGEST for: {}", bookmakerCode);

        var bookmaker = dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOne();

        if (bookmaker == null || !bookmaker.getEnabled()) {
            log.warn("Bookmaker {} not found or disabled", bookmakerCode);
            return;
        }

        BookmakerAdapter adapter = adapters.get(bookmakerCode);
        if (adapter == null) {
            log.error("No adapter for: {}", bookmakerCode);
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            List<RawEvent> rawEvents = adapter.fetchEvents();
            long fetchTime = System.currentTimeMillis() - startTime;

            if (rawEvents.isEmpty()) {
                log.info("{}: 0 событий ({}с)", bookmakerCode.toUpperCase(), fetchTime / 1000);
                return;
            }

            int newEvents = 0;
            int updatedEvents = 0;

            for (RawEvent rawEvent : rawEvents) {
                Long internalEventId = findOrCreateEvent(rawEvent, bookmakerCode);
                if (internalEventId == null) continue;

                boolean isNew = saveMarketsAndOutcomes(rawEvent, internalEventId, bookmakerCode);
                if (isNew) {
                    newEvents++;
                } else {
                    updatedEvents++;
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("{}: {} событий (новых: {}, обновлено: {}, за {}с)",
                    bookmakerCode.toUpperCase(), rawEvents.size(), newEvents, updatedEvents, totalTime / 1000);

            eventPublisher.publishEvent(new OddsUpdatedEvent(bookmakerCode, null));

            // Очистка устаревших событий (используем refresh_seconds из БД, дефолт 300 секунд = 5 минут)
            Integer refreshSeconds = bookmaker.getRefreshSeconds();
            if (refreshSeconds == null || refreshSeconds <= 0) {
                refreshSeconds = 300; // значение по умолчанию
            }
            cleanupOrphanedEvents(bookmaker.getId(), refreshSeconds);

        } catch (Exception e) {
            log.error("{}: {}", bookmakerCode, e.getMessage());
        }
    }

    private boolean saveMarketsAndOutcomes(RawEvent rawEvent, Long internalEventId, String bookmakerCode) {
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        boolean hasNewMarkets = false;

        for (RawMarket rawMarket : rawEvent.getMarkets()) {
            MarketsRecord marketRecord = new MarketsRecord();
            marketRecord.setEventId(internalEventId);
            marketRecord.setBookmakerId(bookmakerId);
            marketRecord.setMarketType(rawMarket.getMarketTypeName());
            marketRecord.setPeriod(rawMarket.getPeriodName());
            marketRecord.setLine(rawMarket.getLine());
            marketRecord.setSourceExternalId(rawMarket.getExternalId());

            // Пытаемся вставить, если конфликт - обновляем
            int result = dsl.insertInto(Tables.MARKETS)
                    .set(marketRecord)
                    .onConflict(Tables.MARKETS.EVENT_ID, Tables.MARKETS.BOOKMAKER_ID,
                            Tables.MARKETS.MARKET_TYPE, Tables.MARKETS.PERIOD, Tables.MARKETS.LINE)
                    .doUpdate()
                    .set(Tables.MARKETS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                    .set(Tables.MARKETS.SOURCE_EXTERNAL_ID, rawMarket.getExternalId())
                    .execute();

            if (result == 1) {
                hasNewMarkets = true;
            }

            // Получаем ID рынка - используем source_external_id для точного поиска
            MarketsRecord savedMarket = dsl.selectFrom(Tables.MARKETS)
                    .where(Tables.MARKETS.SOURCE_EXTERNAL_ID.eq(rawMarket.getExternalId()))
                    .and(Tables.MARKETS.EVENT_ID.eq(internalEventId))
                    .and(Tables.MARKETS.BOOKMAKER_ID.eq(bookmakerId))
                    .fetchAny();  // <-- ЗАМЕНИЛ fetchOne() НА fetchAny()

            if (savedMarket == null) {
                // Fallback: ищем по полям
                savedMarket = dsl.selectFrom(Tables.MARKETS)
                        .where(Tables.MARKETS.EVENT_ID.eq(internalEventId))
                        .and(Tables.MARKETS.BOOKMAKER_ID.eq(bookmakerId))
                        .and(Tables.MARKETS.MARKET_TYPE.eq(rawMarket.getMarketTypeName()))
                        .and(Tables.MARKETS.PERIOD.eq(rawMarket.getPeriodName()))
                        .fetchAny();  // <-- ЗАМЕНИЛ fetchOne() НА fetchAny()
            }

            if (savedMarket == null) continue;

            Long marketId = savedMarket.getId();

            for (RawOutcome rawOutcome : rawMarket.getOutcomes()) {
                dsl.insertInto(Tables.OUTCOMES)
                        .set(Tables.OUTCOMES.MARKET_ID, marketId)
                        .set(Tables.OUTCOMES.OUTCOME_KEY, rawOutcome.getOutcomeKeyName())
                        .set(Tables.OUTCOMES.VALUE, rawOutcome.getOutcomeValueDescription())
                        .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                        .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                        .onConflict(Tables.OUTCOMES.MARKET_ID, Tables.OUTCOMES.OUTCOME_KEY)
                        .doUpdate()
                        .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                        .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                        .set(Tables.OUTCOMES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .execute();
            }
        }

        return hasNewMarkets;
    }


//    private Long findOrCreateInternalEvent(RawEvent rawEvent, String bookmakerCode) {
//        // Проверяем, не слишком ли старое событие (например, старше 2 часов)
//        LocalDateTime now = LocalDateTime.now();
//        if (rawEvent.getStartTime().isBefore(now.minusHours(2))) {
//            log.debug("Skipping old event: {} vs {} (start time: {})",
//                    rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getStartTime());
//            return null;
//        }
//
//        Optional<EventExternalRefsRecord> existingRefOpt = dsl.selectFrom(Tables.EVENT_EXTERNAL_REFS)
//                .where(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.eq(
//                        dsl.select(Tables.BOOKMAKERS.ID).from(Tables.BOOKMAKERS).where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode)).limit(1)
//                ))
//                .and(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID.eq(rawEvent.getExternalId()))
//                .fetchOptional();
//
//        if (existingRefOpt.isPresent()) {
//            Long eventId = existingRefOpt.get().getEventId();
//            if (eventId != null) {
//                log.debug("Found existing internal event ID: {} for external ID: {}", eventId, rawEvent.getExternalId());
//                return eventId;
//            }
//        }
//
//        // Пытаемся найти существующее событие по командам и времени (в пределах 1 часа)
//        Long sportId = 1L; // football по умолчанию
//
//        Long homeTeamId = findOrCreateTeam(rawEvent.getHomeTeamName(), sportId);
//        Long awayTeamId = findOrCreateTeam(rawEvent.getAwayTeamName(), sportId);
//
//        if (homeTeamId == null || awayTeamId == null) {
//            log.warn("Could not find or create teams for event: {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
//            return null;
//        }
//
//        // Ищем существующее событие с теми же командами и временем в пределах 1 часа
//        EventsRecord existingEvent = dsl.selectFrom(Tables.EVENTS)
//                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
//                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
//                .and(Tables.EVENTS.START_TIME.between(
//                        rawEvent.getStartTime().minusHours(1).atOffset(ZoneOffset.UTC),
//                        rawEvent.getStartTime().plusHours(1).atOffset(ZoneOffset.UTC)
//                ))
//                .fetchOneInto(EventsRecord.class);
//
//        if (existingEvent != null) {
//            log.debug("Found existing event by teams: {} vs {} (ID: {})",
//                    rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), existingEvent.getId());
//            return existingEvent.getId();
//        }
//
//        // Создаём новое событие только если оно не слишком старое и не найдено существующее
//        if (rawEvent.getStartTime().isBefore(now.minusHours(1))) {
//            log.debug("Not creating new event for old match: {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
//            return null;
//        }
//
//        // Создаём новое событие
//        EventsRecord eventRecord = new EventsRecord();
//        eventRecord.setHomeTeamId(homeTeamId);
//        eventRecord.setAwayTeamId(awayTeamId);
//        eventRecord.setStartTime(rawEvent.getStartTime().atOffset(ZoneOffset.UTC));
//        eventRecord.setStatus("SCHEDULED");
//        eventRecord.setEventUrl(rawEvent.getEventUrl());
//        eventRecord.setBookmakerCode(bookmakerCode);  // <-- ВОТ ЭТА СТРОЧКА БЫЛА ОТСУТСТВОВАЛА!
//
//        EventsRecord savedEvent = dsl.insertInto(Tables.EVENTS)
//                .set(eventRecord)
//                .returning(Tables.EVENTS.ID)
//                .fetchOne();
//
//        if (savedEvent == null) {
//            log.error("Failed to create new event for {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
//            return null;
//        }
//
//        Long internalEventId = savedEvent.getId();
//        log.debug("Created new event ID: {} for {} vs {} (bookmaker: {})",
//                internalEventId, rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), bookmakerCode);
//
//        // Сохраняем внешнюю ссылку
//        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
//                .from(Tables.BOOKMAKERS)
//                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
//                .limit(1)
//                .fetchOneInto(Long.class);
//
//        if (bookmakerId != null) {
//            dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
//                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, internalEventId)
//                    .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
//                    .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
//                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
//                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
//                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, rawEvent.getStartTime().atOffset(ZoneOffset.UTC))
//                    .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
//                    .doUpdate()
//                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, internalEventId)
//                    .execute();
//        }
//
//        return internalEventId;
//    }


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

    /**
     * Находит существующее каноническое событие по сырым данным с матчингом (включая лиги)
     */
    private Long findCanonicalEventId(RawEvent rawEvent, String bookmakerCode) {
        log.info("🔍 Матчинг события: {} vs {} [{}] от {}",
                rawEvent.getHomeTeamName(),
                rawEvent.getAwayTeamName(),
                rawEvent.getLeagueName(),
                bookmakerCode);

        // 1. Проверяем, есть ли уже внешняя ссылка для этого БК
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        if (bookmakerId == null) return null;

        // Проверяем существующую внешнюю ссылку
        EventExternalRefsRecord existingRef = dsl.selectFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID.eq(rawEvent.getExternalId()))
                .fetchOne();

        if (existingRef != null && existingRef.getEventId() != null) {
            log.debug("Найдена существующая внешняя ссылка для события ID: {}", existingRef.getEventId());
            return existingRef.getEventId();
        }

        // 2. Ищем похожие события через EventMatcherService с учётом времени (±2 часа)
        OffsetDateTime startTimeUtc = rawEvent.getStartTime().atOffset(ZoneOffset.UTC);

        List<EventsRecord> candidateEvents = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.START_TIME.between(
                        startTimeUtc.minusHours(2),
                        startTimeUtc.plusHours(2)
                ))
                .and(Tables.EVENTS.STATUS.eq("SCHEDULED"))
                .fetch();

        EventMatcherService matcher = new EventMatcherService();

        for (EventsRecord candidate : candidateEvents) {
            // Получаем команды кандидата
            TeamsRecord homeTeam = dsl.selectFrom(Tables.TEAMS)
                    .where(Tables.TEAMS.ID.eq(candidate.getHomeTeamId()))
                    .fetchOne();
            TeamsRecord awayTeam = dsl.selectFrom(Tables.TEAMS)
                    .where(Tables.TEAMS.ID.eq(candidate.getAwayTeamId()))
                    .fetchOne();

            if (homeTeam == null || awayTeam == null) continue;

            // Получаем лигу кандидата
            String candidateLeague = null;
            if (candidate.getLeagueId() != null) {
                LeaguesRecord league = dsl.selectFrom(Tables.LEAGUES)
                        .where(Tables.LEAGUES.ID.eq(candidate.getLeagueId()))
                        .fetchOne();
                if (league != null) {
                    candidateLeague = league.getName();
                }
            }

            // Сравниваем через матчер (с учётом лиг!)
            if (matcher.isSameEvent(
                    rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName(),
                    homeTeam.getCanonicalName(), awayTeam.getCanonicalName(), candidateLeague)) {

                log.info("✅ Сматчено событие: {} vs {} [{}] → существующее событие ID: {}",
                        rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName(),
                        candidate.getId());

                // Сохраняем внешнюю ссылку
                if (existingRef == null) {
                    dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                            .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, candidate.getId())
                            .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
                            .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
                            .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                            .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                            .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, startTimeUtc)
                            .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                            .doUpdate()
                            .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, candidate.getId())
                            .execute();
                }

                return candidate.getId();
            }
        }

//        // ВРЕМЕННО: не создаём новые события
//        log.warn("⚠️ ВРЕМЕННО: Не создаём новое событие для {} vs {}, пропускаем",
//                rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
//        return null;

//        // 3. Не нашли — создаём новое событие
//        log.info("🆕 Событие не найдено, создаём новое: {} vs {} [{}]",
//                rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName());
//        // В конце, если не нашли:
        log.info("🆕 Событие не найдено, создаём новое: {} vs {} [{}]",
                rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName());
        return createNewEvent(rawEvent, bookmakerCode);
    }

    /**
     * Создаёт новое каноническое событие (с лигой)
     */
    private Long createNewEvent(RawEvent rawEvent, String bookmakerCode) {
        Long sportId = 1L; // football по умолчанию

        // 1. Создаём или находим команды
        Long homeTeamId = findOrCreateTeamWithNormalization(rawEvent.getHomeTeamName(), sportId, bookmakerCode);
        Long awayTeamId = findOrCreateTeamWithNormalization(rawEvent.getAwayTeamName(), sportId, bookmakerCode);

        if (homeTeamId == null || awayTeamId == null) {
            log.warn("Не удалось создать команды для {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        // 2. Создаём или находим лигу
        Long leagueId = findOrCreateLeague(rawEvent.getLeagueName(), sportId);

        // 3. Создаём событие
        EventsRecord eventRecord = new EventsRecord();
        eventRecord.setHomeTeamId(homeTeamId);
        eventRecord.setAwayTeamId(awayTeamId);
        eventRecord.setLeagueId(leagueId);
        eventRecord.setStartTime(rawEvent.getStartTime().atOffset(ZoneOffset.UTC));
        eventRecord.setStatus("SCHEDULED");
        eventRecord.setEventUrl(rawEvent.getEventUrl());
        eventRecord.setBookmakerCode(bookmakerCode);

        EventsRecord savedEvent = dsl.insertInto(Tables.EVENTS)
                .set(eventRecord)
                .returning(Tables.EVENTS.ID)
                .fetchOne();

        if (savedEvent == null) return null;

        Long eventId = savedEvent.getId();
        log.info("✅ Создано новое событие ID: {} для {} vs {} [{}]",
                eventId, rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName());

        // 4. Сохраняем внешнюю ссылку
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        if (bookmakerId != null) {
            dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, eventId)
                    .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
                    .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, rawEvent.getStartTime().atOffset(ZoneOffset.UTC))
                    .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                    .doUpdate()
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, eventId)
                    .execute();
        }

        return eventId;
    }

    private Long findOrCreateEvent(RawEvent rawEvent, String bookmakerCode) {
        Long sportId = 1L;

        Long homeTeamId = findOrCreateTeamWithNormalization(rawEvent.getHomeTeamName(), sportId, bookmakerCode);
        Long awayTeamId = findOrCreateTeamWithNormalization(rawEvent.getAwayTeamName(), sportId, bookmakerCode);

        if (homeTeamId == null || awayTeamId == null) {
            log.warn("Не удалось создать команды для {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        Long leagueId = findOrCreateLeague(rawEvent.getLeagueName(), sportId);
        OffsetDateTime startTimeUtc = rawEvent.getStartTime().atOffset(ZoneOffset.UTC);

        EventsRecord existingEvent = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                .and(Tables.EVENTS.START_TIME.between(
                        startTimeUtc.minusHours(2),
                        startTimeUtc.plusHours(2)
                ))
                .fetchOne();

        if (existingEvent != null) {
            // Спам-логи убраны — только обновляем ссылку без вывода
            Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                    .from(Tables.BOOKMAKERS)
                    .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                    .fetchOneInto(Long.class);

            if (bookmakerId != null) {
                dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                        .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, existingEvent.getId())
                        .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
                        .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
                        .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                        .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                        .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, startTimeUtc)
                        .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                        .doUpdate()
                        .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, existingEvent.getId())
                        .execute();
            }
            return existingEvent.getId();
        }

        EventsRecord eventRecord = new EventsRecord();
        eventRecord.setHomeTeamId(homeTeamId);
        eventRecord.setAwayTeamId(awayTeamId);
        eventRecord.setLeagueId(leagueId);
        eventRecord.setStartTime(startTimeUtc);
        eventRecord.setStatus("SCHEDULED");
        eventRecord.setEventUrl(rawEvent.getEventUrl());
        eventRecord.setBookmakerCode(bookmakerCode);

        EventsRecord savedEvent = dsl.insertInto(Tables.EVENTS)
                .set(eventRecord)
                .returning(Tables.EVENTS.ID)
                .fetchOne();

        if (savedEvent == null) return null;

        Long eventId = savedEvent.getId();
        log.info("✅ Создано новое событие ID: {} для {} vs {} [{}]",
                eventId, rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(), rawEvent.getLeagueName());

        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        if (bookmakerId != null) {
            dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, eventId)
                    .set(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, bookmakerId)
                    .set(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID, rawEvent.getExternalId())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_HOME, rawEvent.getHomeTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_AWAY, rawEvent.getAwayTeamName())
                    .set(Tables.EVENT_EXTERNAL_REFS.RAW_START_TIME, startTimeUtc)
                    .onConflict(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID, Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID)
                    .doUpdate()
                    .set(Tables.EVENT_EXTERNAL_REFS.EVENT_ID, eventId)
                    .execute();
        }

        return eventId;
    }


    /**
     * Находит или создаёт лигу
     */
    /**
     * Находит или создаёт лигу
     */
    private Long findOrCreateLeague(String leagueName, Long sportId) {
        if (leagueName == null || leagueName.isEmpty() || "Unknown League".equals(leagueName)) {
            return null; // лига не обязательна
        }

        // Нормализуем название лиги для поиска
        String normalizedLeagueName = leagueName.trim().toLowerCase();

        // Ищем существующую лигу
        LeaguesRecord existingLeague = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.likeIgnoreCase(normalizedLeagueName))
                .and(Tables.LEAGUES.SPORT_ID.eq(sportId))
                .fetchOne();

        if (existingLeague != null) {
            return existingLeague.getId();
        }

        // Создаём новую лигу
        LeaguesRecord newLeague = new LeaguesRecord();
        newLeague.setName(leagueName);
        newLeague.setSportId(sportId);

        LeaguesRecord savedLeague = dsl.insertInto(Tables.LEAGUES)
                .set(newLeague)
                .returning(Tables.LEAGUES.ID)
                .fetchOne();

        log.debug("Создана новая лига: {} (ID: {})", leagueName, savedLeague.getId());
        return savedLeague.getId();
    }

    /**
     * Находит или создаёт команду с нормализацией через TeamMatcher
     */
    private Long findOrCreateTeamWithNormalization(String teamName, Long sportId, String bookmakerCode) {
        if (teamName == null || teamName.isEmpty() || "TBD".equals(teamName)) {
            return null;
        }

        // 1. Сначала ищем через алиасы (точное совпадение у конкретного БК)
        Optional<TeamsRecord> byAlias = teamMatcher.findCanonicalTeamByAliasAndBookmaker(teamName, bookmakerCode);
        if (byAlias.isPresent()) {
            log.debug("Команда найдена по алиасу: {} → ID: {}", teamName, byAlias.get().getId());
            return byAlias.get().getId();
        }

        // 2. Нормализуем имя и ищем
        String normalized = teamMatcher.normalizeTeamName(teamName);
        Optional<TeamsRecord> byNormalized = teamMatcher.findCanonicalTeam(normalized);
        if (byNormalized.isPresent()) {
            // Создаём алиас для будущих раз
            createTeamAlias(byNormalized.get().getId(), bookmakerCode, teamName);
            log.debug("Команда найдена по нормализации: {} → ID: {}", teamName, byNormalized.get().getId());
            return byNormalized.get().getId();
        }

        // 3. Создаём новую команду
        TeamsRecord newTeam = teamMatcher.createCanonicalTeam(teamName, sportId);

        // Создаём алиас
        createTeamAlias(newTeam.getId(), bookmakerCode, teamName);

        log.info("Создана новая команда: {} (ID: {})", teamName, newTeam.getId());
        return newTeam.getId();
    }

    /**
     * Создаёт алиас команды для букмекера
     */
    private void createTeamAlias(Long teamId, String bookmakerCode, String alias) {
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        if (bookmakerId == null) return;

        dsl.insertInto(Tables.TEAM_ALIASES)
                .set(Tables.TEAM_ALIASES.TEAM_ID, teamId)
                .set(Tables.TEAM_ALIASES.BOOKMAKER_ID, bookmakerId)
                .set(Tables.TEAM_ALIASES.ALIAS, alias)
                .onConflict(Tables.TEAM_ALIASES.TEAM_ID, Tables.TEAM_ALIASES.BOOKMAKER_ID, Tables.TEAM_ALIASES.ALIAS)
                .doNothing()
                .execute();
    }

    /**
     * Очищает устаревшие ссылки на события для указанного букмекера
     * @param bookmakerId ID букмекера
     * @param refreshSeconds интервал обновления в секундах (из настроек БК)
     */
    /**
     * Очищает устаревшие ссылки на события для указанного букмекера
     * @param bookmakerId ID букмекера
     * @param refreshSeconds интервал обновления в секундах (из настроек БК)
     */
    private void cleanupOrphanedEvents(Long bookmakerId, int refreshSeconds) {
        log.debug("Starting cleanup of orphaned events for bookmaker ID: {}", bookmakerId);

        // Порог: если событие не обновлялось дольше, чем 2 цикла парсинга
        Duration staleThreshold = Duration.ofSeconds(refreshSeconds * 2L);
        OffsetDateTime cutoffTime = OffsetDateTime.now(ZoneOffset.UTC).minus(staleThreshold);

        // Находим устаревшие external_refs для этого БК
        var staleRefs = dsl.selectFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.eq(bookmakerId))
                .and(Tables.EVENT_EXTERNAL_REFS.LAST_SEEN_AT.lessThan(cutoffTime))
                .fetch();

        if (staleRefs.isEmpty()) {
            log.debug("No stale references found for bookmaker ID: {}", bookmakerId);
            return;
        }

        log.info("Found {} stale references for bookmaker ID: {}", staleRefs.size(), bookmakerId);

        for (var staleRef : staleRefs) {
            Long eventId = staleRef.getEventId();
            String externalId = staleRef.getExternalId();
            OffsetDateTime lastSeen = staleRef.getLastSeenAt();

            log.warn("Cleaning up stale reference: bookmaker_id={}, external_id={}, event_id={}, last_seen={}",
                    bookmakerId, externalId, eventId, lastSeen);

            if (eventId == null) {
                // Если event_id не привязан — просто удаляем ссылку
                dsl.deleteFrom(Tables.EVENT_EXTERNAL_REFS)
                        .where(Tables.EVENT_EXTERNAL_REFS.ID.eq(staleRef.getId()))
                        .execute();
                log.debug("Deleted unlinked external_ref with id={}", staleRef.getId());
                continue;
            }

            // Проверяем, есть ли у этого события другие активные букмекеры
            int otherBookmakersCount = dsl.fetchCount(
                    dsl.selectDistinct(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID)
                            .from(Tables.EVENT_EXTERNAL_REFS)
                            .where(Tables.EVENT_EXTERNAL_REFS.EVENT_ID.eq(eventId))
                            .and(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.ne(bookmakerId))
            );

            if (otherBookmakersCount == 0) {
                // Событие больше не нужно — удаляем каскадно
                log.info("Event {} has no other bookmakers, deleting completely", eventId);
                deleteEventCascade(eventId);
            } else {
                // Удаляем только устаревшую ссылку этого БК
                dsl.deleteFrom(Tables.EVENT_EXTERNAL_REFS)
                        .where(Tables.EVENT_EXTERNAL_REFS.ID.eq(staleRef.getId()))
                        .execute();
                log.debug("Removed stale reference for event {} (other bookmakers exist)", eventId);
            }
        }
    }

    /**
     * Каскадное удаление события и всех связанных данных
     * @param eventId ID события
     */
    private void deleteEventCascade(Long eventId) {
        // 1. Удаляем исходы (outcomes) через рынки (markets)
        dsl.deleteFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.MARKET_ID.in(
                        dsl.select(Tables.MARKETS.ID)
                                .from(Tables.MARKETS)
                                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                ))
                .execute();

        // 2. Удаляем рынки (markets)
        dsl.deleteFrom(Tables.MARKETS)
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .execute();

        // 3. Удаляем внешние ссылки (event_external_refs)
        dsl.deleteFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.EVENT_ID.eq(eventId))
                .execute();

        // 4. Помечаем событие как FINISHED (вместо удаления, чтобы сохранить историю)
        dsl.update(Tables.EVENTS)
                .set(Tables.EVENTS.STATUS, "FINISHED")
                .where(Tables.EVENTS.ID.eq(eventId))
                .execute();

        log.info("Event {} marked as FINISHED and all related data cleaned up", eventId);
    }



}