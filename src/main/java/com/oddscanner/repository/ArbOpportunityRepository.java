package com.oddscanner.repository;

import com.oddscanner.generated.tables.records.ArbOpportunitiesRecord;
import java.util.List;
import java.util.Optional;

public interface ArbOpportunityRepository {
    List<ArbOpportunitiesRecord> findAll();
    List<ArbOpportunitiesRecord> findAllSortedByProfitDesc(); // <-- Добавь эту строку
    Optional<ArbOpportunitiesRecord> findById(Long id);
    Optional<ArbOpportunitiesRecord> findByEventIdAndMarketSignature(Long eventId, String marketSignature);
    ArbOpportunitiesRecord save(ArbOpportunitiesRecord record);
    void updateStatus(Long id, String status);
}