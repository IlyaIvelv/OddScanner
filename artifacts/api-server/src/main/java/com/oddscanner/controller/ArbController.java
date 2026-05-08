package com.oddscanner.controller;

import com.oddscanner.model.Arb;
import com.oddscanner.model.Event;
import com.oddscanner.repository.ArbRepository;
import com.oddscanner.repository.EventRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/arbs")
@RequiredArgsConstructor
public class ArbController {

    private final ArbRepository arbRepository;
    private final EventRepository eventRepository;

    @GetMapping
    public Map<String, Object> listArbs(
            @RequestParam(defaultValue = "true") boolean active,
            @RequestParam(defaultValue = "0") double minProfit,
            @RequestParam(defaultValue = "50") int limit
    ) {
        List<Arb> arbs = active
                ? arbRepository.findActiveWithMinProfit(BigDecimal.valueOf(minProfit))
                : arbRepository.findAllByOrderByFoundAtDesc();

        List<Map<String, Object>> result = arbs.stream()
                .limit(Math.min(limit, 200))
                .map(this::enrichArb)
                .collect(Collectors.toList());

        return Map.of("arbs", result, "total", result.size());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getArb(@PathVariable Long id) {
        return arbRepository.findById(id)
                .map(arb -> ResponseEntity.ok(enrichArb(arb)))
                .orElse(ResponseEntity.notFound().build());
    }

    private Map<String, Object> enrichArb(Arb arb) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", arb.getId());
        map.put("eventId", arb.getEventId());
        map.put("marketType", arb.getMarketType());
        map.put("profitPct", arb.getProfitPct());
        map.put("legs", arb.getLegs());
        map.put("isActive", arb.isActive());
        map.put("foundAt", arb.getFoundAt());

        eventRepository.findById(arb.getEventId()).ifPresent(event -> {
            map.put("homeTeam", event.getHomeTeam());
            map.put("awayTeam", event.getAwayTeam());
            map.put("sport", event.getSport());
            map.put("startsAt", event.getStartsAt());
        });

        return map;
    }
}
