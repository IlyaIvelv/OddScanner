package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.SportsRecord;
import java.util.List;
import java.util.Optional;

public interface SportRepository {
    List<SportsRecord> findAll();
    Optional<SportsRecord> findByCode(String code);
    Optional<SportsRecord> findById(Long id);
    SportsRecord save(SportsRecord record);
}