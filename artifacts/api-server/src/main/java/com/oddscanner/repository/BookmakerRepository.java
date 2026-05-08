package com.oddscanner.repository;

import com.oddscanner.model.Bookmaker;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface BookmakerRepository extends JpaRepository<Bookmaker, Long> {
    Optional<Bookmaker> findBySlug(String slug);
    List<Bookmaker> findAllByIsActiveTrue();
}
