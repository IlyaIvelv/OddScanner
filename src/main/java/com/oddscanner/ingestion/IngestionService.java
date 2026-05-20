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
import com.oddscanner.utils.ConsoleColors;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static com.oddscanner.generated.tables.Teams.TEAMS;

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

    @Scheduled(fixedDelayString = "${scheduler.fixed-delay-ms:300000}", initialDelay = 5000)
    public void scheduledIngestion() {

        var allBookmakers = dsl.selectFrom(Tables.BOOKMAKERS).fetch();
        for (var bm : allBookmakers) {
            log.info("  Букмекер: code='{}', name='{}', enabled={}",
                    bm.getCode(), bm.getName(), ConsoleColors.status(bm.getEnabled()));
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
                logErrorLocation(e);
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

            Integer refreshSeconds = bookmaker.getRefreshSeconds();
            if (refreshSeconds == null || refreshSeconds <= 0) {
                refreshSeconds = 300;
            }
            cleanupOrphanedEvents(bookmaker.getId(), refreshSeconds);

        } catch (Exception e) {
            log.error("{}: {}", bookmakerCode, e.getMessage(), e);
            logErrorLocation(e);
        }
    }

    private boolean saveMarketsAndOutcomes(RawEvent rawEvent, Long internalEventId, String bookmakerCode) {
        Long bookmakerId = dsl.select(Tables.BOOKMAKERS.ID)
                .from(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(Long.class);

        if (bookmakerId == null) {
            log.error("Bookmaker not found: {}", bookmakerCode);
            return false;
        }

        boolean hasNewMarkets = false;

        for (RawMarket rawMarket : rawEvent.getMarkets()) {
            MarketsRecord existingMarket = dsl.selectFrom(Tables.MARKETS)
                    .where(Tables.MARKETS.EVENT_ID.eq(internalEventId))
                    .and(Tables.MARKETS.BOOKMAKER_ID.eq(bookmakerId))
                    .and(Tables.MARKETS.MARKET_TYPE.eq(rawMarket.getMarketTypeName()))
                    .and(Tables.MARKETS.PERIOD.eq(rawMarket.getPeriodName()))
                    .fetchAny();

            MarketsRecord marketRecord;

            if (existingMarket == null) {
                marketRecord = new MarketsRecord();
                marketRecord.setEventId(internalEventId);
                marketRecord.setBookmakerId(bookmakerId);
                marketRecord.setMarketType(rawMarket.getMarketTypeName());
                marketRecord.setPeriod(rawMarket.getPeriodName());
                marketRecord.setLine(rawMarket.getLine());
                marketRecord.setSourceExternalId(rawMarket.getExternalId());
                marketRecord.setUpdatedAt(OffsetDateTime.now(ZoneOffset.UTC));

                marketRecord = dsl.insertInto(Tables.MARKETS)
                        .set(marketRecord)
                        .returning(Tables.MARKETS.ID)
                        .fetchOne();

                if (marketRecord != null) {
                    hasNewMarkets = true;
                    log.debug("Created new market: {}", marketRecord.getId());
                }
            } else {
                marketRecord = existingMarket;
                dsl.update(Tables.MARKETS)
                        .set(Tables.MARKETS.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                        .set(Tables.MARKETS.SOURCE_EXTERNAL_ID, rawMarket.getExternalId())
                        .where(Tables.MARKETS.ID.eq(marketRecord.getId()))
                        .execute();
                log.debug("Updated existing market: {}", marketRecord.getId());
            }

            if (marketRecord == null) {
                log.warn("Failed to get market record for event: {}", internalEventId);
                continue;
            }

            Long marketId = marketRecord.getId();

            for (RawOutcome rawOutcome : rawMarket.getOutcomes()) {
                if (rawOutcome.getOdds() == null || rawOutcome.getOdds().compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }

                OutcomesRecord existingOutcome = dsl.selectFrom(Tables.OUTCOMES)
                        .where(Tables.OUTCOMES.MARKET_ID.eq(marketId))
                        .and(Tables.OUTCOMES.OUTCOME_KEY.eq(rawOutcome.getOutcomeKeyName()))
                        .fetchAny();

                if (existingOutcome == null) {
                    dsl.insertInto(Tables.OUTCOMES)
                            .set(Tables.OUTCOMES.MARKET_ID, marketId)
                            .set(Tables.OUTCOMES.OUTCOME_KEY, rawOutcome.getOutcomeKeyName())
                            .set(Tables.OUTCOMES.VALUE, rawOutcome.getOutcomeValueDescription())
                            .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                            .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                            .set(Tables.OUTCOMES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .execute();
                    log.debug("Created new outcome: {} for market: {}", rawOutcome.getOutcomeKeyName(), marketId);
                } else {
                    dsl.update(Tables.OUTCOMES)
                            .set(Tables.OUTCOMES.ODDS, rawOutcome.getOdds())
                            .set(Tables.OUTCOMES.IS_ACTIVE, rawOutcome.isActive())
                            .set(Tables.OUTCOMES.UPDATED_AT, OffsetDateTime.now(ZoneOffset.UTC))
                            .where(Tables.OUTCOMES.ID.eq(existingOutcome.getId()))
                            .execute();
                    log.debug("Updated outcome: {} for market: {}", rawOutcome.getOutcomeKeyName(), marketId);
                }
            }
        }

        return hasNewMarkets;
    }

    private void logErrorLocation(Exception e) {
        StackTraceElement[] stackTrace = e.getStackTrace();
        for (StackTraceElement element : stackTrace) {
            if (element.getClassName().contains("IngestionService")) {
                log.error("Ошибка в методе: {} строка: {}",
                        element.getMethodName(),
                        element.getLineNumber());
                break;
            }
        }
    }

    private Long findOrCreateEvent(RawEvent rawEvent, String bookmakerCode) {
        Long sportId = 1L;

        Long homeTeamId = findOrCreateTeamWithNormalization(rawEvent.getHomeTeamName(), sportId);
        Long awayTeamId = findOrCreateTeamWithNormalization(rawEvent.getAwayTeamName(), sportId);

        if (homeTeamId == null || awayTeamId == null) {
            log.warn("Не удалось создать команды для {} vs {}", rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName());
            return null;
        }

        Long leagueId = findOrCreateLeague(rawEvent.getLeagueName(), sportId);
        OffsetDateTime startTimeUtc = rawEvent.getStartTime().atOffset(ZoneOffset.UTC);

        // Ищем существующее событие по ID команд
        EventsRecord existingEvent = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                .fetchAny();

        if (existingEvent != null) {
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

            // ✅ ОБНОВЛЯЕМ URL, если он изменился (например, стал правильным)
            if (rawEvent.getEventUrl() != null && !rawEvent.getEventUrl().equals(existingEvent.getEventUrl())) {
                dsl.update(Tables.EVENTS)
                        .set(Tables.EVENTS.EVENT_URL, rawEvent.getEventUrl())
                        .set(Tables.EVENTS.HOME_TEAM, rawEvent.getHomeTeamName())      // ← ДОБАВИТЬ
                        .set(Tables.EVENTS.AWAY_TEAM, rawEvent.getAwayTeamName())      // ← ДОБАВИТЬ
                        .where(Tables.EVENTS.ID.eq(existingEvent.getId()))
                        .execute();
                log.info("🔄 Обновлён URL для события {}: {}", existingEvent.getId(), rawEvent.getEventUrl());
            }

            return existingEvent.getId();
        }

        // СОЗДАЁМ НОВОЕ СОБЫТИЕ
        EventsRecord eventRecord = new EventsRecord();
        eventRecord.setHomeTeamId(homeTeamId);
        eventRecord.setAwayTeamId(awayTeamId);
        eventRecord.setHomeTeam(rawEvent.getHomeTeamName());      // ← ДОБАВИТЬ!
        eventRecord.setAwayTeam(rawEvent.getAwayTeamName());      // ← ДОБАВИТЬ!
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
        log.info("✅ Создано новое событие ID: {} для {} vs {} [{}], URL: {}",
                eventId, rawEvent.getHomeTeamName(), rawEvent.getAwayTeamName(),
                rawEvent.getLeagueName(), rawEvent.getEventUrl());

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

    private Long findOrCreateTeam(String teamName, Long sportId) {
        if (teamName == null || teamName.isEmpty() || "TBD".equals(teamName)) {
            return null;
        }

        TeamsRecord existingTeam = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(teamName))
                .fetchOneInto(TeamsRecord.class);

        if (existingTeam != null) {
            return existingTeam.getId();
        }

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

    public void printBookmakersStatus() {
        var bookmakers = dsl.select(
                        Tables.BOOKMAKERS.ID,
                        Tables.BOOKMAKERS.CODE,
                        Tables.BOOKMAKERS.NAME,
                        Tables.BOOKMAKERS.ENABLED
                )
                .from(Tables.BOOKMAKERS)
                .fetch();

        var activeBookmakers = bookmakers.stream()
                .filter(bm -> bm.get(Tables.BOOKMAKERS.ENABLED))
                .toList();

        String activeNames = activeBookmakers.stream()
                .map(bm -> bm.get(Tables.BOOKMAKERS.NAME))
                .collect(Collectors.joining(", "));

        log.info("=== СТАТУС БУКМЕКЕРОВ ===");
        log.info("📊 Найдено активных букмекеров: {} ({})", activeBookmakers.size(), activeNames);

        for (var bm : bookmakers) {
            String status;
            if (bm.get(Tables.BOOKMAKERS.ENABLED)) {
                status = "\u001B[32m✅ АКТИВЕН\u001B[0m";
            } else {
                status = "\u001B[31m❌ НЕ АКТИВЕН\u001B[0m";
            }

            String registered = adapters.containsKey(bm.get(Tables.BOOKMAKERS.CODE))
                    ? " (зарегистрирован)"
                    : " (НЕ зарегистрирован)";

            log.info("- ID: {}, Code: {}, Name: {} - {}{}",
                    bm.get(Tables.BOOKMAKERS.ID),
                    bm.get(Tables.BOOKMAKERS.CODE),
                    bm.get(Tables.BOOKMAKERS.NAME),
                    status,
                    registered);
        }
        log.info("=======================");
    }

    private Long findOrCreateLeague(String leagueName, Long sportId) {
        if (leagueName == null || leagueName.isEmpty() || "Unknown League".equals(leagueName)) {
            return null;
        }

        String normalizedLeagueName = leagueName.trim().toLowerCase();

        LeaguesRecord existingLeague = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.likeIgnoreCase(normalizedLeagueName))
                .and(Tables.LEAGUES.SPORT_ID.eq(sportId))
                .fetchOne();

        if (existingLeague != null) {
            return existingLeague.getId();
        }

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

    private Long findOrCreateTeamWithNormalization(String teamName, Long sportId) {
        if (teamName == null || teamName.isBlank()) return null;

        String normalized = normalizeTeamName(teamName);

        var existing = dsl.select(TEAMS.ID)
                .from(TEAMS)
                .where(TEAMS.NORMALIZED_NAME.eq(normalized))
                .fetchOne();

        if (existing != null) {
            return existing.get(TEAMS.ID);
        }

        return dsl.insertInto(TEAMS)
                .set(TEAMS.SPORT_ID, sportId)
                .set(TEAMS.CANONICAL_NAME, teamName)
                .set(TEAMS.NORMALIZED_NAME, normalized)
                .returning(TEAMS.ID)
                .fetchOne()
                .get(TEAMS.ID);
    }

    private String normalizeTeamName(String name) {
        if (name == null) return null;
        return name.toLowerCase()
                .trim()
                .replaceAll("[^a-zа-яё\\s]", "")
                .replaceAll("\\s+", " ");
    }

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

    private void cleanupOrphanedEvents(Long bookmakerId, int refreshSeconds) {
        log.debug("Starting cleanup of orphaned events for bookmaker ID: {}", bookmakerId);

        Duration staleThreshold = Duration.ofSeconds(refreshSeconds * 2L);
        OffsetDateTime cutoffTime = OffsetDateTime.now(ZoneOffset.UTC).minus(staleThreshold);

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
                dsl.deleteFrom(Tables.EVENT_EXTERNAL_REFS)
                        .where(Tables.EVENT_EXTERNAL_REFS.ID.eq(staleRef.getId()))
                        .execute();
                log.debug("Deleted unlinked external_ref with id={}", staleRef.getId());
                continue;
            }

            int otherBookmakersCount = dsl.fetchCount(
                    dsl.selectDistinct(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID)
                            .from(Tables.EVENT_EXTERNAL_REFS)
                            .where(Tables.EVENT_EXTERNAL_REFS.EVENT_ID.eq(eventId))
                            .and(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.ne(bookmakerId))
            );

            if (otherBookmakersCount == 0) {
                log.info("Event {} has no other bookmakers, deleting completely", eventId);
                deleteEventCascade(eventId);
            } else {
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
        // 1. Получаем все market_id для этого события
        var marketIds = dsl.select(Tables.MARKETS.ID)
                .from(Tables.MARKETS)
                .where(Tables.MARKETS.EVENT_ID.eq(eventId))
                .fetch(Tables.MARKETS.ID);

        if (!marketIds.isEmpty()) {
            // 2. Получаем все outcome_id для этих market_id
            var outcomeIds = dsl.select(Tables.OUTCOMES.ID)
                    .from(Tables.OUTCOMES)
                    .where(Tables.OUTCOMES.MARKET_ID.in(marketIds))
                    .fetch(Tables.OUTCOMES.ID);

            if (!outcomeIds.isEmpty()) {
                // 3. Удаляем ноги вилок, которые ссылаются на эти исходы
                dsl.deleteFrom(Tables.ARB_LEGS)
                        .where(Tables.ARB_LEGS.OUTCOME_ID.in(outcomeIds))
                        .execute();

                // 4. Удаляем сами исходы
                dsl.deleteFrom(Tables.OUTCOMES)
                        .where(Tables.OUTCOMES.ID.in(outcomeIds))
                        .execute();
            }

            // 5. Удаляем рынки
            dsl.deleteFrom(Tables.MARKETS)
                    .where(Tables.MARKETS.ID.in(marketIds))
                    .execute();
        }

        // 6. Удаляем внешние ссылки
        dsl.deleteFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.EVENT_ID.eq(eventId))
                .execute();

        // 7. Помечаем событие как FINISHED (вместо удаления, чтобы сохранить историю)
        dsl.update(Tables.EVENTS)
                .set(Tables.EVENTS.STATUS, "FINISHED")
                .where(Tables.EVENTS.ID.eq(eventId))
                .execute();

        log.info("Event {} marked as FINISHED and all related data cleaned up", eventId);
    }}