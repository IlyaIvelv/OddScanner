// File: src/main/java/com/oddscanner/repository/JooqArbLegRepository.java (если ещё не создан)
package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.ArbLegsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public class JooqArbLegRepository implements ArbLegRepository {

    private final DSLContext dsl;

    public JooqArbLegRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<ArbLegsRecord> findAll() {
        return dsl.selectFrom(Tables.ARB_LEGS).fetchInto(ArbLegsRecord.class);
    }

    @Override
    public List<ArbLegsRecord> findByArbId(Long arbId) {
        return dsl.selectFrom(Tables.ARB_LEGS)
                .where(Tables.ARB_LEGS.ARB_ID.eq(arbId))
                .fetchInto(ArbLegsRecord.class);
    }

    @Override
    public ArbLegsRecord save(ArbLegsRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.ARB_LEGS)
                    .set(record)
                    .returningResult(Tables.ARB_LEGS.fields())
                    .fetchOne()
                    .into(ArbLegsRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.ARB_LEGS)
                    .set(record)
                    .where(Tables.ARB_LEGS.ID.eq(record.getId()))
                    .returningResult(Tables.ARB_LEGS.fields())
                    .fetchOne()
                    .into(ArbLegsRecord.class);
        }
    }

    @Override
    public void deleteByArbId(Long arbId) {
        dsl.deleteFrom(Tables.ARB_LEGS)
                .where(Tables.ARB_LEGS.ARB_ID.eq(arbId))
                .execute();
    }
}