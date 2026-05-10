// File: src/main/java/com/oddscanner/bookmaker/marathon/MarathonAdapter.java

package com.oddscanner.bookmaker.marathon;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.microsoft.playwright.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class MarathonAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);

    private static final String BASE_URL = "https://www.marathonbet.com";

    @Override
    public String code() {
        return "marathon";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("Fetching events from {} using Playwright", code());

        List<RawEvent> events = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage"))); // Исправлено: List.of()

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            log.debug("Navigating to {}", BASE_URL);
            page.navigate(BASE_URL);

            // --- TODO: Реализовать парсинг списка событий из HTML ---
            log.warn("TODO: Implement event parsing logic here for {}", code());
            // Пример: String htmlContent = page.content();
            // Извлечь события (ID, команды, время) из HTML
            // RawEvent event = new RawEvent(...);
            // events.add(event);
            // ----------------------------------------------------

            browser.close();
        } catch (Exception e) {
            log.error("Error fetching events from {}", code(), e);
        }
        return events;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        log.info("Fetching markets for event {} from {} using Playwright", externalEventId, code());

        List<RawMarket> markets = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage"))); // Исправлено: List.of()

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            // --- TODO: Реализовать навигацию к странице события и парсинг рынков ---
            log.warn("TODO: Implement market parsing logic for event {} on {}", externalEventId, code());
            // Пример: String eventUrl = BASE_URL + "/en/betting/event/" + externalEventId;
            // page.navigate(eventUrl);
            // String htmlContent = page.content();
            // Извлечь рынки из HTML
            // RawMarket market = new RawMarket(...);
            // markets.add(market);
            // -------------------------------------------------------------------------

            browser.close();
        } catch (Exception e) {
            log.error("Error fetching markets for event {} from {}", externalEventId, code(), e);
        }
        return markets;
    }
}