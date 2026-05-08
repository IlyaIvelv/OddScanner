package com.oddscanner.repository;

import com.oddscanner.model.SchedulerConfig;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface SchedulerConfigRepository extends JpaRepository<SchedulerConfig, Long> {
}
