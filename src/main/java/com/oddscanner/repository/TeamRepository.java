package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.TeamsRecord;
import java.util.List;
import java.util.Optional;

public interface TeamRepository {
    List<TeamsRecord> findAll();
    Optional<TeamsRecord> findById(Long id);
    Optional<TeamsRecord> findByCanonicalName(String canonicalName);
    Optional<TeamsRecord> findByNormalizedName(String normalizedName); // Для fuzzy поиска
    TeamsRecord save(TeamsRecord record);
}