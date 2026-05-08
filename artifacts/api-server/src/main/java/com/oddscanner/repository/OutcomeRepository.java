package com.oddscanner.repository;

import com.oddscanner.model.Outcome;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OutcomeRepository extends JpaRepository<Outcome, Long> {
    List<Outcome> findByMarketId(Long marketId);
    Optional<Outcome> findByMarketIdAndName(Long marketId, String name);
}
