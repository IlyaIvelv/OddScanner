package com.oddscanner.controller;

import com.oddscanner.model.Bookmaker;
import com.oddscanner.model.ScrapeJob;
import com.oddscanner.repository.BookmakerRepository;
import com.oddscanner.repository.ScrapeJobRepository;
import com.oddscanner.scraper.ScraperRegistry;
import com.oddscanner.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/scrape")
@RequiredArgsConstructor
public class ScrapeController {

    private final SchedulerService schedulerService;
    private final ScrapeJobRepository scrapeJobRepository;
    private final BookmakerRepository bookmakerRepository;
    private final ScraperRegistry scraperRegistry;

    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> trigger() {
        try {
            int arbsFound = schedulerService.triggerNow();
            return ResponseEntity.ok(Map.of("success", true, "arbsFound", arbsFound));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("success", false, "error", e.getMessage()));
        }
    }

    @GetMapping("/jobs")
    public Map<String, Object> listJobs(@RequestParam(defaultValue = "20") int limit) {
        int safeLimit = Math.min(limit, 100);
        List<ScrapeJob> jobs = scrapeJobRepository.findAllByOrderByStartedAtDesc(PageRequest.of(0, safeLimit));

        Map<Long, String> bookmakerNames = new HashMap<>();
        bookmakerRepository.findAll().forEach(bk -> bookmakerNames.put(bk.getId(), bk.getName()));

        List<Map<String, Object>> enriched = jobs.stream().map(j -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", j.getId());
            map.put("bookmakerId", j.getBookmakerId());
            map.put("bookmakerName", bookmakerNames.getOrDefault(j.getBookmakerId(), "unknown"));
            map.put("status", j.getStatus());
            map.put("eventsFound", j.getEventsFound());
            map.put("marketsFound", j.getMarketsFound());
            map.put("error", j.getError());
            map.put("startedAt", j.getStartedAt());
            map.put("finishedAt", j.getFinishedAt());
            return map;
        }).collect(Collectors.toList());

        return Map.of("jobs", enriched);
    }

    @GetMapping("/bookmakers")
    public Map<String, Object> listBookmakers() {
        List<Map<String, String>> list = scraperRegistry.getAll().stream()
                .map(s -> Map.of("slug", s.getSlug(), "name", s.getName()))
                .collect(Collectors.toList());
        return Map.of("bookmakers", list);
    }
}
