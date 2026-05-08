package com.oddscanner.repository;

import com.oddscanner.model.ScrapeJob;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScrapeJobRepository extends JpaRepository<ScrapeJob, Long> {
    List<ScrapeJob> findAllByOrderByStartedAtDesc(Pageable pageable);
}
