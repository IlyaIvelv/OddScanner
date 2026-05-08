package com.oddscanner.repository;

import com.oddscanner.model.Arb;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.util.List;

@Repository
public interface ArbRepository extends JpaRepository<Arb, Long> {
    List<Arb> findAllByIsActiveTrueOrderByFoundAtDesc();

    @Query("SELECT a FROM Arb a WHERE a.isActive = true AND a.profitPct >= :minProfit ORDER BY a.foundAt DESC")
    List<Arb> findActiveWithMinProfit(@Param("minProfit") BigDecimal minProfit);

    List<Arb> findAllByOrderByFoundAtDesc();
}
