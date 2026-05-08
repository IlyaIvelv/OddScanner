package com.oddscanner.repository;

import com.oddscanner.model.Market;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface MarketRepository extends JpaRepository<Market, Long> {
    List<Market> findByEventId(Long eventId);
    Optional<Market> findByEventIdAndBookmakerIdAndType(Long eventId, Long bookmakerId, String type);
}
