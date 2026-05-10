package com.oddscanner.repository;

import com.oddscanner.generated.Tables; // Импортируем Tables
import com.oddscanner.generated.tables.records.SportsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JooqSportRepository implements SportRepository {

    private final DSLContext dsl;

    public JooqSportRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<SportsRecord> findAll() {
        return dsl.selectFrom(Tables.SPORTS).fetchInto(SportsRecord.class);
    }

    @Override
    public Optional<SportsRecord> findByCode(String code) {
        SportsRecord record = dsl.selectFrom(Tables.SPORTS)
                .where(Tables.SPORTS.CODE.eq(code))
                .fetchOneInto(SportsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<SportsRecord> findById(Long id) {
        SportsRecord record = dsl.selectFrom(Tables.SPORTS)
                .where(Tables.SPORTS.ID.eq(id))
                .fetchOneInto(SportsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public SportsRecord save(SportsRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.SPORTS)
                    .set(record)
                    .returningResult(Tables.SPORTS.fields())
                    .fetchOne()
                    .into(SportsRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.SPORTS)
                    .set(record)
                    .where(Tables.SPORTS.ID.eq(record.getId()))
                    .returningResult(Tables.SPORTS.fields())
                    .fetchOne()
                    .into(SportsRecord.class);
        }
    }
}