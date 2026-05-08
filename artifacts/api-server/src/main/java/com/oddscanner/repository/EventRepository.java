package com.oddscanner.repository;

import com.oddscanner.model.Event;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    Optional<Event> findByHomeTeamAndAwayTeamAndSport(String homeTeam, String awayTeam, String sport);
    List<Event> findAllByOrderByStartsAtDesc();
    List<Event> findBySportOrderByStartsAtDesc(String sport);
}
