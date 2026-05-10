package com.oddscanner.repository;

import com.oddscanner.generated.Tables; // Импортируем Tables
import com.oddscanner.generated.tables.records.BookmakersRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public class JooqBookmakerRepository implements BookmakerRepository {

    private final DSLContext dsl;

    public JooqBookmakerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public List<BookmakersRecord> findAllEnabled() {
        // Используем Tables.BOOKMAKERS, если он существует
        return dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.ENABLED.isTrue())
                .fetchInto(BookmakersRecord.class); // fetchInto позволяет маппить в Record
    }

    @Override
    public Optional<BookmakersRecord> findByCode(String code) {
        BookmakersRecord record = dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.CODE.eq(code))
                .fetchOneInto(BookmakersRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public Optional<BookmakersRecord> findById(Long id) {
        BookmakersRecord record = dsl.selectFrom(Tables.BOOKMAKERS)
                .where(Tables.BOOKMAKERS.ID.eq(id))
                .fetchOneInto(BookmakersRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public BookmakersRecord save(BookmakersRecord record) {
        if (record.getId() == null) {
            // INSERT
            return dsl.insertInto(Tables.BOOKMAKERS)
                    .set(record)
                    .returningResult(Tables.BOOKMAKERS.fields())
                    .fetchOne()
                    .into(BookmakersRecord.class);
        } else {
            // UPDATE
            return dsl.update(Tables.BOOKMAKERS)
                    .set(record)
                    .where(Tables.BOOKMAKERS.ID.eq(record.getId()))
                    .returningResult(Tables.BOOKMAKERS.fields())
                    .fetchOne()
                    .into(BookmakersRecord.class);
        }
    }
}