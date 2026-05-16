package com.oddscanner.controller;

import com.oddscanner.ingestion.IngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/debug")
@RequiredArgsConstructor
public class DebugController {

    private final IngestionService ingestionService;

    @PostMapping("/ingest/{bookmakerCode}")
    public String ingestBookmaker(@PathVariable String bookmakerCode) {
        ingestionService.ingest(bookmakerCode);
        return "Ingestion started for: " + bookmakerCode;
    }

    @GetMapping("/status")
    public String status() {
        ingestionService.printBookmakersStatus();
        return "Check logs for status";
    }
}