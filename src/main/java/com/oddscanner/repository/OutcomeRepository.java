// File: src/main/java/com/oddscanner/repository/OutcomeRepository.java

package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.OutcomesRecord;
import java.util.List;
import java.util.Optional;

public interface OutcomeRepository {
    List<OutcomesRecord> findAll();
    Optional<OutcomesRecord> findById(Long id);
    Optional<OutcomesRecord> findByMarketIdAndOutcomeKey(Long marketId, String outcomeKey);
    OutcomesRecord save(OutcomesRecord record);
}