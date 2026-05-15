// File: src/main/java/com/oddscanner/normalization/JooqEventMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

@Service
public class JooqEventMatcher implements EventMatcher {

    private final DSLContext dsl;
    private final TeamMatcher teamMatcher; // Для получения ID команд, если передаются имена
    private final LeagueMatcher leagueMatcher; // Для получения ID лиги
    private final SportMatcher sportMatcher; // Для получения ID спорта

    public JooqEventMatcher(DSLContext dsl, TeamMatcher teamMatcher, LeagueMatcher leagueMatcher, SportMatcher sportMatcher) {
        this.dsl = dsl;
        this.teamMatcher = teamMatcher;
        this.leagueMatcher = leagueMatcher;
        this.sportMatcher = sportMatcher;
    }

    @Override
    public EventsRecord findOrCreateCanonicalEvent(Long homeTeamId, Long awayTeamId,
                                                   Long leagueId, LocalDateTime startTime,
                                                   String bookmakerCode) {
        OffsetDateTime startTimeUtc = startTime.atOffset(ZoneOffset.UTC);

        // Ищем существующее событие
        EventsRecord existingEvent = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                .and(Tables.EVENTS.START_TIME.eq(startTimeUtc))
                .fetchOneInto(EventsRecord.class);

        if (existingEvent != null) {
            // Если событие есть, но bookmaker_code не заполнен - обновляем
            if (existingEvent.getBookmakerCode() == null && bookmakerCode != null) {
                existingEvent.setBookmakerCode(bookmakerCode);
                existingEvent.update();
            }
            return existingEvent;
        }

        // Создаём новое событие
        EventsRecord newEvent = new EventsRecord();
        newEvent.setHomeTeamId(homeTeamId);
        newEvent.setAwayTeamId(awayTeamId);
        newEvent.setLeagueId(leagueId);
        newEvent.setStartTime(startTimeUtc);
        newEvent.setStatus("SCHEDULED");
        newEvent.setBookmakerCode(bookmakerCode);  // ← НОВОЕ ПОЛЕ

        return dsl.insertInto(Tables.EVENTS)
                .set(newEvent)
                .returning(Tables.EVENTS.fields())
                .fetchOne()
                .into(EventsRecord.class);
    }

    // Метод для нормализации и поиска/создания события по *сырым* данным
    // public EventsRecord findOrCreateEventByRawData(String rawHomeTeamName, String rawAwayTeamName, String rawLeagueName, String rawSportName, LocalDateTime startTime, String bookmakerCode) {
    //     // 1. Нормализовать и найти/создать Sport
    //     String normSportName = sportMatcher.normalizeSportName(rawSportName);
    //     Long sportId = sportMatcher.findCanonicalSportByName(normSportName).map(SportsRecord::getId).orElse(null);
    //     if (sportId == null) {
    //         // Логика обработки неизвестного вида спорта
    //         return null; // или бросить исключение
    //     }
    //
    //     // 2. Нормализовать и найти/создать League
    //     String normLeagueName = leagueMatcher.normalizeLeagueName(rawLeagueName);
    //     Long leagueId = leagueMatcher.findCanonicalLeague(normLeagueName, sportId).map(LeaguesRecord::getId).orElse(null);
    //     if (leagueId == null) {
    //         leagueId = leagueMatcher.createCanonicalLeague(rawLeagueName, sportId).getId();
    //     }
    //
    //     // 3. Нормализовать и найти/создать Home Team
    //     String normHomeName = teamMatcher.normalizeTeamName(rawHomeTeamName);
    //     Long homeTeamId = teamMatcher.findCanonicalTeamByAliasAndBookmaker(rawHomeTeamName, bookmakerCode) // Сначала по алиасу
    //             .or(() -> teamMatcher.findCanonicalTeam(normHomeName)) // Потом по нормальному имени
    //             .map(TeamsRecord::getId).orElse(null);
    //     if (homeTeamId == null) {
    //         homeTeamId = teamMatcher.createCanonicalTeam(rawHomeTeamName, sportId).getId(); // Создаем новую команду
    //     }
    //
    //     // 4. Нормализовать и найти/создать Away Team (аналогично Home Team)
    //     String normAwayName = teamMatcher.normalizeTeamName(rawAwayTeamName);
    //     Long awayTeamId = teamMatcher.findCanonicalTeamByAliasAndBookmaker(rawAwayTeamName, bookmakerCode)
    //             .or(() -> teamMatcher.findCanonicalTeam(normAwayName))
    //             .map(TeamsRecord::getId).orElse(null);
    //     if (awayTeamId == null) {
    //         awayTeamId = teamMatcher.createCanonicalTeam(rawAwayTeamName, sportId).getId();
    //     }
    //
    //     // 5. Найти или создать событие
    //     return findOrCreateCanonicalEvent(homeTeamId, awayTeamId, leagueId, startTime);
    // }
}