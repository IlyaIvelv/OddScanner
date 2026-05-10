package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.TeamsRecord;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

import static org.jooq.impl.DSL.field; // Импортируем DSL.field
import static org.jooq.impl.DSL.val; // Импортируем DSL.val

@Repository
public class JooqTeamRepository implements TeamRepository {

    private final DSLContext dsl;

    public JooqTeamRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<TeamsRecord> findAll() {
        return dsl.selectFrom(Tables.TEAMS).fetchInto(TeamsRecord.class);
    }

    @Override
    public Optional<TeamsRecord> findById(Long id) {
        TeamsRecord record = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.ID.eq(id))
                .fetchOneInto(TeamsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<TeamsRecord> findByCanonicalName(String canonicalName) {
        TeamsRecord record = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(canonicalName))
                .fetchOneInto(TeamsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<TeamsRecord> findByNormalizedName(String normalizedName) {
        TeamsRecord record = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.NORMALIZED_NAME.eq(normalizedName))
                .fetchOneInto(TeamsRecord.class);
        return Optional.ofNullable(record);
    }

    // Пример метода для fuzzy поиска (требует pg_trgm)
    public Optional<TeamsRecord> findClosestByNormalizedOrCanonicalName(String inputName, Double threshold) {
        // Правильный способ вызова функции similarity
        Field<Double> similarityOnNormalized = field("similarity({0}, {1})", Double.class, Tables.TEAMS.NORMALIZED_NAME, val(inputName));
        Field<Double> similarityOnCanonical = field("similarity({0}, {1})", Double.class, Tables.TEAMS.CANONICAL_NAME, val(inputName));

        TeamsRecord record = dsl.selectFrom(Tables.TEAMS)
                .where(
                        similarityOnNormalized.gt(threshold)
                                .or(similarityOnCanonical.gt(threshold))
                )
                .orderBy(
                        similarityOnNormalized.desc(),
                        similarityOnCanonical.desc()
                )
                .limit(1)
                .fetchOneInto(TeamsRecord.class);

        return Optional.ofNullable(record);
    }

    @Override
    public TeamsRecord save(TeamsRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.TEAMS)
                    .set(record)
                    .returningResult(Tables.TEAMS.fields())
                    .fetchOne()
                    .into(TeamsRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.TEAMS)
                    .set(record)
                    .where(Tables.TEAMS.ID.eq(record.getId()))
                    .returningResult(Tables.TEAMS.fields())
                    .fetchOne()
                    .into(TeamsRecord.class);
        }
    }
}