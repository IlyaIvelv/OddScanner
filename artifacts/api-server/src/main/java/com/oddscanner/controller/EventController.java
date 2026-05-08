package com.oddscanner.controller;

import com.oddscanner.model.Market;
import com.oddscanner.model.Outcome;
import com.oddscanner.repository.EventRepository;
import com.oddscanner.repository.MarketRepository;
import com.oddscanner.repository.OutcomeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/events")
@RequiredArgsConstructor
public class EventController {

    private final EventRepository eventRepository;
    private final MarketRepository marketRepository;
    private final OutcomeRepository outcomeRepository;

    @GetMapping
    public Map<String, Object> listEvents(
            @RequestParam(required = false) String sport,
            @RequestParam(defaultValue = "50") int limit
    ) {
        var events = sport != null
                ? eventRepository.findBySportOrderByStartsAtDesc(sport)
                : eventRepository.findAllByOrderByStartsAtDesc();

        var limited = events.stream().limit(Math.min(limit, 200)).collect(Collectors.toList());
        return Map.of("events", limited, "total", limited.size());
    }

    @GetMapping("/{id}/markets")
    public ResponseEntity<Map<String, Object>> getMarkets(@PathVariable Long id) {
        if (!eventRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        List<Market> markets = marketRepository.findByEventId(id);
        List<Map<String, Object>> enriched = markets.stream().map(m -> {
            List<Outcome> outcomes = outcomeRepository.findByMarketId(m.getId());
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", m.getId());
            map.put("bookmakerId", m.getBookmakerId());
            map.put("type", m.getType());
            map.put("name", m.getName());
            map.put("line", m.getLine());
            map.put("updatedAt", m.getUpdatedAt());
            map.put("outcomes", outcomes);
            return map;
        }).collect(Collectors.toList());

        return ResponseEntity.ok(Map.of("markets", enriched));
    }
}
