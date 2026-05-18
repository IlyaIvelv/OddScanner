package com.oddscanner.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

import static com.oddscanner.generated.Tables.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TeamMatchingService {

    private final DSLContext dsl;

    public Integer findOrCreateCanonicalTeam(String teamName, String bookmakerCode) {
        if (teamName == null || teamName.isBlank()) return null;

        // 1. Ищем алиас для этого букмекера
        var aliasRecord = dsl.select(TEAM_ALIASES.CANONICAL_TEAM_ID)
                .from(TEAM_ALIASES)
                .where(TEAM_ALIASES.ALIAS.eq(teamName))
                .and(TEAM_ALIASES.BOOKMAKER_CODE.eq(bookmakerCode))
                .fetchOne();

        if (aliasRecord != null && aliasRecord.get(TEAM_ALIASES.CANONICAL_TEAM_ID) != null) {
            return aliasRecord.get(TEAM_ALIASES.CANONICAL_TEAM_ID);
        }

        // 2. Ищем существующую каноничную команду по названию
        var existing = dsl.select(CANONICAL_TEAMS.ID)
                .from(CANONICAL_TEAMS)
                .where(CANONICAL_TEAMS.NAME.eq(teamName))
                .fetchOne();

        Integer canonicalId;
        if (existing != null) {
            canonicalId = existing.get(CANONICAL_TEAMS.ID);
        } else {
            // 3. Создаём новую каноничную команду
            canonicalId = dsl.insertInto(CANONICAL_TEAMS)
                    .set(CANONICAL_TEAMS.NAME, teamName)
                    .set(CANONICAL_TEAMS.CREATED_AT, LocalDateTime.now())
                    .returning(CANONICAL_TEAMS.ID)
                    .fetchOne()
                    .get(CANONICAL_TEAMS.ID);
            log.info("Создана новая каноничная команда: '{}' (ID: {})", teamName, canonicalId);
        }

        // 4. Сохраняем алиас для будущих матчей
        dsl.insertInto(TEAM_ALIASES)
                .set(TEAM_ALIASES.ALIAS, teamName)
                .set(TEAM_ALIASES.BOOKMAKER_CODE, bookmakerCode)
                .set(TEAM_ALIASES.CANONICAL_TEAM_ID, canonicalId)
                .set(TEAM_ALIASES.CREATED_AT, LocalDateTime.now())
                .onDuplicateKeyIgnore()
                .execute();

        return canonicalId;
    }
}