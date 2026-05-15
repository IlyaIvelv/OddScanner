package com.oddscanner.bookmaker.fonbet;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class FonbetAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FonbetAdapter.class);
    // ИСПРАВЛЕННЫЙ URL
    private static final String BASE_URL = "https://fon.bet/sports/football";
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("/event/(\\d+)");

    @Override
    public String code() {
        return "fonbet";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА FONBET ===");
        log.info("Fetching events from {} using Playwright", code());

        List<RawEvent> events = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)  // Можно поставить false для отладки
                    .setSlowMo(100)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            log.info("Navigating to {}", BASE_URL);
            page.navigate(BASE_URL);
            // Ждём, пока пройдут основные сетевые запросы
            page.waitForLoadState(LoadState.NETWORKIDLE);
            // Дополнительная задержка для подгрузки динамического контента
            page.waitForTimeout(5000);

            // --- УНИВЕРСАЛЬНЫЕ СЕЛЕКТОРЫ ДЛЯ ПОИСКА СОБЫТИЙ ---
            // Ищем блоки, которые могут содержать название команды
            List<ElementHandle> possibleBlocks = page.querySelectorAll(".team-name, .member-name, .event-cell, .live-event-item, a[href*='/event/']");

            log.info("Found {} potential event elements on Fonbet", possibleBlocks.size());

            for (ElementHandle block : possibleBlocks) {
                try {
                    String text = block.textContent().trim();
                    if (text == null || text.isEmpty() || text.length() < 5) continue;

                    String homeTeam = "Unknown";
                    String awayTeam = "TBD";

                    // Пробуем извлечь команды из текста
                    if (text.contains(" - ")) {
                        String[] parts = text.split(" - ");
                        if (parts.length >= 2) {
                            homeTeam = parts[0].trim();
                            awayTeam = parts[1].trim();
                        }
                    } else if (text.contains(" vs ")) {
                        String[] parts = text.split(" vs ");
                        if (parts.length >= 2) {
                            homeTeam = parts[0].trim();
                            awayTeam = parts[1].trim();
                        }
                    }

                    // Пробуем найти ID события из href
                    String externalId = "unknown";
                    String href = block.getAttribute("href");
                    if (href != null && href.contains("/event/")) {
                        Matcher m = EVENT_ID_PATTERN.matcher(href);
                        if (m.find()) {
                            externalId = m.group(1);
                        }
                    } else {
                        // Если у текущего блока нет href, ищем ссылку внутри или родителя
                        ElementHandle link = block.querySelector("a[href*='/event/']");
                        if (link == null) {
                            link = block.querySelector("xpath=ancestor::a[contains(@href, '/event/')]");
                        }
                        if (link != null) {
                            String linkHref = link.getAttribute("href");
                            if (linkHref != null) {
                                Matcher m = EVENT_ID_PATTERN.matcher(linkHref);
                                if (m.find()) {
                                    externalId = m.group(1);
                                }
                            }
                        }
                    }

                    // Если ID так и не нашли или команды не распарсились, пропускаем это событие
                    if ("unknown".equals(externalId) || "Unknown".equals(homeTeam) || "TBD".equals(awayTeam)) {
                        log.debug("Skipping event with insufficient data: ID={}, home={}, away={}", externalId, homeTeam, awayTeam);
                        continue;
                    }

                    RawEvent event = new RawEvent(
                            externalId,
                            homeTeam,
                            awayTeam,
                            LocalDateTime.now().plusHours(2),
                            "Football",
                            null,
                            new ArrayList<>()
                    );
                    events.add(event);
                    log.debug("Parsed Fonbet event: {} vs {} (ID: {})", homeTeam, awayTeam, externalId);

                } catch (Exception e) {
                    log.debug("Error parsing Fonbet event block: {}", e.getMessage());
                }
            }

            //browser.close();
        } catch (Exception e) {
            log.error("Error fetching events from {}", code(), e);
        }

        log.info("=== FONBET: Found {} events ===", events.size());
        return events;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        log.info("Fetching markets for Fonbet event {}", externalEventId);

        List<RawMarket> markets = new ArrayList<>();

        if ("unknown".equals(externalEventId)) {
            log.warn("Skipping markets fetch for Fonbet event with unknown ID");
            return markets;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            // Используем актуальную структуру URL Фонбета для страницы события
            String eventUrl = "https://fon.bet/event/" + externalEventId;
            log.info("Navigating to Fonbet event page: {}", eventUrl);
            page.navigate(eventUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(3000);

            // Универсальный поиск коэффициентов
            List<RawOutcome> mainOutcomes = new ArrayList<>();
            List<ElementHandle> outcomes = page.querySelectorAll(".coefficient, .odd, [class*='coef'], .price, .bet-item-odds");

            for (ElementHandle outcome : outcomes) {
                try {
                    String oddsText = outcome.textContent().trim();
                    if (oddsText == null || oddsText.isEmpty()) continue;

                    oddsText = oddsText.replace(",", ".");
                    BigDecimal odds = new BigDecimal(oddsText);

                    mainOutcomes.add(new RawOutcome(
                            "OUTCOME_" + mainOutcomes.size(),
                            "value_" + mainOutcomes.size(),
                            oddsText,
                            odds,
                            true
                    ));
                } catch (Exception e) {
                    log.debug("Error parsing Fonbet outcome: {}", e.getMessage());
                }
            }

            if (!mainOutcomes.isEmpty()) {
                markets.add(new RawMarket(
                        "MAIN",
                        "FULL_TIME",
                        null,
                        externalEventId,
                        externalEventId,
                        null,
                        mainOutcomes
                ));
                log.info("Found {} outcomes for Fonbet event {}", mainOutcomes.size(), externalEventId);
            }

            //browser.close();
        } catch (Exception e) {
            log.error("Error fetching markets for Fonbet event {}", externalEventId, e);
        }

        return markets;
    }
}