package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventExternalRefsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class JooqEventExternalRefRepository implements EventExternalRefRepository {

    private final DSLContext dsl;

    public JooqEventExternalRefRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<EventExternalRefsRecord> findByExternalIdAndBookmakerCode(String externalId, String bookmakerCode) {
        EventExternalRefsRecord record = dsl.selectFrom(Tables.EVENT_EXTERNAL_REFS)
                .where(Tables.EVENT_EXTERNAL_REFS.EXTERNAL_ID.eq(externalId))
                .and(Tables.EVENT_EXTERNAL_REFS.BOOKMAKER_ID.eq(
                        dsl.select(Tables.BOOKMAKERS.ID).from(Tables.BOOKMAKERS).where(Tables.BOOKMAKERS.CODE.eq(bookmakerCode)).limit(1)
                ))
                .fetchOneInto(EventExternalRefsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public EventExternalRefsRecord save(EventExternalRefsRecord record) {
        if (record.getId() == null) {
            return dsl.insertInto(Tables.EVENT_EXTERNAL_REFS)
                    .set(record)
                    .returning(Tables.EVENT_EXTERNAL_REFS.fields())
                    .fetchOne()
                    .into(EventExternalRefsRecord.class);
        } else {
            return dsl.update(Tables.EVENT_EXTERNAL_REFS)
                    .set(record)
                    .where(Tables.EVENT_EXTERNAL_REFS.ID.eq(record.getId()))
                    .returning(Tables.EVENT_EXTERNAL_REFS.fields())
                    .fetchOne()
                    .into(EventExternalRefsRecord.class);
        }
    }
}