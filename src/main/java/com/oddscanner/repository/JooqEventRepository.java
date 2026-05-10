// File: src/main/java/com/oddscanner/repository/JooqEventRepository.java
package com.oddscanner.repository;

import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

@Repository
public class JooqEventRepository implements EventRepository {

    private final DSLContext dsl;

    public JooqEventRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public Optional<EventsRecord> findByHomeTeamIdAndAwayTeamIdAndStartTime(Long homeTeamId, Long awayTeamId, LocalDateTime startTime) {
        // ИСПРАВЛЕНО: Преобразуем LocalDateTime в OffsetDateTime
        OffsetDateTime offsetStartTime = startTime.atOffset(ZoneOffset.UTC);

        EventsRecord record = dsl.selectFrom(Tables.EVENTS)
                .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                // ИСПРАВЛЕНО: Используем OffsetDateTime
                .and(Tables.EVENTS.START_TIME.eq(offsetStartTime))
                .fetchOneInto(EventsRecord.class);
        return Optional.ofNullable(record);
    }

    @Override
    public EventsRecord save(EventsRecord record) {
        if (record.getId() == null) {
            return dsl.insertInto(Tables.EVENTS)
                    .set(record)
                    .returning(Tables.EVENTS.fields())
                    .fetchOne()
                    .into(EventsRecord.class);
        } else {
            return dsl.update(Tables.EVENTS)
                    .set(record)
                    .where(Tables.EVENTS.ID.eq(record.getId()))
                    .returning(Tables.EVENTS.fields())
                    .fetchOne()
                    .into(EventsRecord.class);
        }
    }
}