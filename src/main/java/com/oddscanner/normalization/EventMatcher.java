package com.oddscanner.normalization;

import com.oddscanner.generated.tables.records.EventsRecord;
import java.time.LocalDateTime;

public interface EventMatcher {
    // Находит или создает событие на основе нормализованных данных
    EventsRecord findOrCreateCanonicalEvent(Long homeTeamId, Long awayTeamId, Long leagueId, LocalDateTime startTime, String bookmakerCode);
}