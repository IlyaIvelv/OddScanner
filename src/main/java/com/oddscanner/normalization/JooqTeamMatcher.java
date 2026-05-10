// File: src/main/java/com/oddscanner/normalization/JooqTeamMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.TeamsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class JooqTeamMatcher implements TeamMatcher {

    private final DSLContext dsl;

    public JooqTeamMatcher(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<TeamsRecord> findCanonicalTeam(String normalizedName) {
        // Ищем по normalized_name (точное совпадение)
        TeamsRecord record = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.NORMALIZED_NAME.eq(normalizedName.toLowerCase())) // Предполагаем, что нормализация приводит к lower()
                .fetchOneInto(TeamsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<TeamsRecord> findCanonicalTeamByAliasAndBookmaker(String aliasName, String bookmakerCode) {
        // Ищем через team_aliases
        TeamsRecord record = dsl.select(Tables.TEAMS.fields()) // Выбираем поля из teams
                .from(Tables.TEAMS)
                .join(Tables.TEAM_ALIASES).on(Tables.TEAM_ALIASES.TEAM_ID.eq(Tables.TEAMS.ID))
                .join(Tables.BOOKMAKERS).on(Tables.BOOKMAKERS.ID.eq(Tables.TEAM_ALIASES.BOOKMAKER_ID))
                .where(Tables.TEAM_ALIASES.ALIAS.eq(aliasName))
                .and(Tables.BOOKMAKERS.CODE.eq(bookmakerCode))
                .fetchOneInto(TeamsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public TeamsRecord createCanonicalTeam(String canonicalName, Long sportId) {
        TeamsRecord newTeam = new TeamsRecord();
        newTeam.setCanonicalName(canonicalName);
        newTeam.setSportId(sportId);
        newTeam.setNormalizedName(normalizeTeamName(canonicalName)); // Устанавливаем нормализованное имя при создании

        return dsl.insertInto(Tables.TEAMS)
                .set(newTeam)
                .returning(Tables.TEAMS.fields())
                .fetchOne()
                .into(TeamsRecord.class);
    }

    @Override
    public String normalizeTeamName(String rawName) {
        // Простая нормализация: приведение к нижнему регистру, удаление лишних пробелов
        // В реальности можно добавить сопоставление сокращений ("Man Utd" -> "Manchester United"), удаление суффиксов ("FC", "CF", "Club") и т.д.
        return rawName.trim().toLowerCase();
    }
}