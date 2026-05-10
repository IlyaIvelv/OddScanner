package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.ArbLegsRecord;
import java.util.List;

public interface ArbLegRepository {
    List<ArbLegsRecord> findAll();
    List<ArbLegsRecord> findByArbId(Long arbId); // Найти ноги по ID вилки
    ArbLegsRecord save(ArbLegsRecord record);
    void deleteByArbId(Long arbId);
}