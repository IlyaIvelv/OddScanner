package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.EventsRecord;
import java.util.Optional;

public interface EventRepository {
    Optional<EventsRecord> findByHomeTeamIdAndAwayTeamIdAndStartTime(Long homeTeamId, Long awayTeamId, java.time.LocalDateTime startTime);
    EventsRecord save(EventsRecord record);
}