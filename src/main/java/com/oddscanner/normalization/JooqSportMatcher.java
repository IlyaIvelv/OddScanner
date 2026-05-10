// File: src/main/java/com/oddscanner/normalization/JooqSportMatcher.java

package com.oddscanner.normalization;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.SportsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
public class JooqSportMatcher implements SportMatcher {

    private final DSLContext dsl;

    public JooqSportMatcher(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<SportsRecord> findCanonicalSportByCode(String code) {
        SportsRecord record = dsl.selectFrom(Tables.SPORTS)
                .where(Tables.SPORTS.CODE.eq(code))
                .fetchOneInto(SportsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<SportsRecord> findCanonicalSportByName(String normalizedName) {
        SportsRecord record = dsl.selectFrom(Tables.SPORTS)
                .where(Tables.SPORTS.NAME.eq(normalizedName.toLowerCase()))
                .fetchOneInto(SportsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public String normalizeSportName(String rawName) {
        // Простая нормализация
        return rawName.trim().toLowerCase();
    }
}