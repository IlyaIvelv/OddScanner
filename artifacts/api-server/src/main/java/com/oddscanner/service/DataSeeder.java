package com.oddscanner.service;

import com.oddscanner.model.Bookmaker;
import com.oddscanner.repository.BookmakerRepository;
import com.oddscanner.scraper.ScraperRegistry;
import com.oddscanner.scraper.BaseScraper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DataSeeder implements ApplicationRunner {

    private final BookmakerRepository bookmakerRepository;
    private final ScraperRegistry scraperRegistry;

    @Override
    public void run(ApplicationArguments args) {
        for (BaseScraper scraper : scraperRegistry.getAll()) {
            bookmakerRepository.findBySlug(scraper.getSlug()).orElseGet(() -> {
                Bookmaker bk = new Bookmaker();
                bk.setName(scraper.getName());
                bk.setSlug(scraper.getSlug());
                bk.setBaseUrl("https://placeholder.url");
                bk.setActive(true);
                Bookmaker saved = bookmakerRepository.save(bk);
                log.info("Seeded bookmaker: {}", scraper.getSlug());
                return saved;
            });
        }
    }
}
