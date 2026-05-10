package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.EventExternalRefsRecord;
import java.util.Optional;

public interface EventExternalRefRepository {
    Optional<EventExternalRefsRecord> findByExternalIdAndBookmakerCode(String externalId, String bookmakerCode);
    EventExternalRefsRecord save(EventExternalRefsRecord record);
}