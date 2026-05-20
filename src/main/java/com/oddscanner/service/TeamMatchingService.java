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

    public Long findOrCreateCanonicalTeam(String teamName, String bookmakerCode) {
        if (teamName == null || teamName.isBlank()) return null;

        // Временно отключаем, так как таблицы CANONICAL_TEAMS нет в БД
        log.warn("TeamMatchingService временно отключен из-за отсутствия таблицы canonical_teams");
        return null;

        /* TODO: раскомментировать после создания таблиц canonical_teams
        // 1. Ищем алиас для этого букмекера
        var aliasRecord = dsl.select(TEAM_ALIASES.TEAM_ID)
                .from(TEAM_ALIASES)
                .where(TEAM_ALIASES.ALIAS.eq(teamName))
                .and(TEAM_ALIASES.BOOKMAKER_ID.eq(Long.parseLong(bookmakerCode)))
                .fetchOne();

        if (aliasRecord != null && aliasRecord.get(TEAM_ALIASES.TEAM_ID) != null) {
            return aliasRecord.get(TEAM_ALIASES.TEAM_ID);
        }

        // 2. Ищем существующую команду в таблице teams
        var existing = dsl.select(TEAMS.ID)
                .from(TEAMS)
                .where(TEAMS.NAME.eq(teamName))
                .fetchOne();

        Long canonicalId;
        if (existing != null) {
            canonicalId = existing.get(TEAMS.ID);
        } else {
            // 3. Создаём новую команду
            canonicalId = dsl.insertInto(TEAMS)
                    .set(TEAMS.NAME, teamName)
                    .returning(TEAMS.ID)
                    .fetchOne()
                    .get(TEAMS.ID);
            log.info("Создана новая команда: '{}' (ID: {})", teamName, canonicalId);
        }

        // 4. Сохраняем алиас для будущих матчей
        dsl.insertInto(TEAM_ALIASES)
                .set(TEAM_ALIASES.ALIAS, teamName)
                .set(TEAM_ALIASES.BOOKMAKER_ID, Long.parseLong(bookmakerCode))
                .set(TEAM_ALIASES.TEAM_ID, canonicalId)
                .onDuplicateKeyIgnore()
                .execute();

        return canonicalId;
        */
    }
}