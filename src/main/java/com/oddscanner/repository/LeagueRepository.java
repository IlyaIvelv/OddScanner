package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.LeaguesRecord;
import java.util.List;
import java.util.Optional;

public interface LeagueRepository {
    List<LeaguesRecord> findAll();
    Optional<LeaguesRecord> findById(Long id);
    Optional<LeaguesRecord> findByNameAndCountry(String name, String country); // Пример метода
    LeaguesRecord save(LeaguesRecord record);
}