package com.oddscanner.scraper;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Registry of all bookmaker scrapers.
 * To add a new bookmaker: create a new class extending BaseScraper,
 * annotate with @Component, and it will be auto-registered here.
 */
@Component
public class ScraperRegistry {

    private final List<BaseScraper> scrapers;

    public ScraperRegistry(List<BaseScraper> scrapers) {
        this.scrapers = scrapers;
    }

    public List<BaseScraper> getAll() {
        return scrapers;
    }

    public Optional<BaseScraper> findBySlug(String slug) {
        return scrapers.stream()
                .filter(s -> s.getSlug().equals(slug))
                .findFirst();
    }
}
