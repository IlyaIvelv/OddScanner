// File: src/main/java/com/oddscanner/normalization/JooqLeagueMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.LeaguesRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class JooqLeagueMatcher implements LeagueMatcher {

    private final DSLContext dsl;

    public JooqLeagueMatcher(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<LeaguesRecord> findCanonicalLeague(String normalizedName, Long sportId) {
        LeaguesRecord record = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.eq(normalizedName.toLowerCase())) // Опять же, предполагаем, что нормализация приводит к lower()
                .and(Tables.LEAGUES.SPORT_ID.eq(sportId))
                .fetchOneInto(LeaguesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public LeaguesRecord createCanonicalLeague(String canonicalName, Long sportId) {
        LeaguesRecord newLeague = new LeaguesRecord();
        newLeague.setName(canonicalName);
        newLeague.setSportId(sportId);

        return dsl.insertInto(Tables.LEAGUES)
                .set(newLeague)
                .returning(Tables.LEAGUES.fields())
                .fetchOne()
                .into(LeaguesRecord.class);
    }

    @Override
    public String normalizeLeagueName(String rawName) {
        // Простая нормализация
        return rawName.trim().toLowerCase();
    }
}