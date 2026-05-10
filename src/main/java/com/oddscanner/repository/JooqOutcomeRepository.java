// File: src/main/java/com/oddscanner/repository/JooqOutcomeRepository.java

package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.OutcomesRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JooqOutcomeRepository implements OutcomeRepository {

    private final DSLContext dsl;

    public JooqOutcomeRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<OutcomesRecord> findAll() {
        return dsl.selectFrom(Tables.OUTCOMES).fetchInto(OutcomesRecord.class);
    }

    @Override
    public Optional<OutcomesRecord> findById(Long id) {
        OutcomesRecord record = dsl.selectFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.ID.eq(id))
                .fetchOneInto(OutcomesRecord.class);
        return Optional.ofNullable(record);
    }

    // ИСПРАВЛЕНО: Подпись метода теперь соответствует интерфейсу
    @Override
    public Optional<OutcomesRecord> findByMarketIdAndOutcomeKey(Long marketId, String outcomeKey) {
        OutcomesRecord record = dsl.selectFrom(Tables.OUTCOMES)
                .where(Tables.OUTCOMES.MARKET_ID.eq(marketId)) // ИСПРАВЛЕНО: MARKET_ID вместо EXTERNAL_MARKET_ID
                .and(Tables.OUTCOMES.OUTCOME_KEY.eq(outcomeKey))
                .fetchOneInto(OutcomesRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public OutcomesRecord save(OutcomesRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.OUTCOMES)
                    .set(record)
                    .returningResult(Tables.OUTCOMES.fields())
                    .fetchOne()
                    .into(OutcomesRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.OUTCOMES)
                    .set(record)
                    .where(Tables.OUTCOMES.ID.eq(record.getId()))
                    .returningResult(Tables.OUTCOMES.fields())
                    .fetchOne()
                    .into(OutcomesRecord.class);
        }
    }
}