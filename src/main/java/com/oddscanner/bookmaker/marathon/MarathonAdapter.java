// File: src/main/java/com/oddscanner/bookmaker/marathon/MarathonAdapter.java
package com.oddscanner.bookmaker.marathon;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.microsoft.playwright.*;
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
public class MarathonAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);
    private static final String BASE_URL = "https://www.marathonbet.ru/su/betting/Football";
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("/event/(\\d+)");

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
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            // Увеличиваем таймаут и добавляем ожидание загрузки контента
            page.navigate(BASE_URL, new Page.NavigateOptions().setTimeout(60000));
            page.waitForSelector("tr[data-event-id]", new Page.WaitForSelectorOptions().setTimeout(30000));

            // Извлекаем все события
            List<ElementHandle> eventRows = page.querySelectorAll("tr[data-event-id]");

            for (ElementHandle eventRow : eventRows) {
                try {
                    // Извлекаем данные о событии
                    String homeTeam = eventRow.querySelector("td:nth-child(2)").textContent().trim();
                    String awayTeam = eventRow.querySelector("td:nth-child(3)").textContent().trim();
                    String externalId = eventRow.getAttribute("data-event-id");

                    // Парсим время матча
                    String timeText = eventRow.querySelector("td:nth-child(4)").textContent().trim();
                    LocalDateTime startTime = parseMatchTime(timeText);

                    // Создаем RawEvent с правильным количеством параметров
                    RawEvent event = new RawEvent(
                            externalId,
                            homeTeam,
                            awayTeam,
                            startTime,
                            "Football",  // sportName
                            null,        // leagueName
                            new ArrayList<>() // markets
                    );

                    events.add(event);
                    log.debug("Parsed event: {} vs {} (ID: {})", homeTeam, awayTeam, externalId);
                } catch (Exception e) {
                    log.error("Error parsing event row", e);
                }
            }

            browser.close();
        } catch (Exception e) {
            log.error("Error fetching events from {}", code(), e);
        }

        log.info("Found {} events from {}", events.size(), code());
        return events;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        log.info("Fetching markets for event {} from {}", externalEventId, code());

        List<RawMarket> markets = new ArrayList<>();
        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setViewportSize(1920, 1080)
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"));

            Page page = context.newPage();

            // Переходим на страницу конкретного события
            String eventUrl = "https://www.marathonbet.ru/su/betting/event/" + externalEventId;
            page.navigate(eventUrl, new Page.NavigateOptions().setTimeout(60000));
            page.waitForSelector(".market-group", new Page.WaitForSelectorOptions().setTimeout(30000));

            // Извлекаем основные рынки (П1, Х, П2)
            List<RawOutcome> mainOutcomes = new ArrayList<>();
            List<ElementHandle> mainMarketRows = page.querySelectorAll(".market-group:first-child .outcome-row");

            for (ElementHandle row : mainMarketRows) {
                String outcomeKey = row.querySelector(".outcome-name").textContent().trim();
                String oddsText = row.querySelector(".outcome-coefficient").textContent().trim();
                BigDecimal odds = new BigDecimal(oddsText.replace(",", "."));

                // ИСПРАВЛЕНО: добавлен недостающий параметр outcomeValueDescription
                mainOutcomes.add(new RawOutcome(
                        outcomeKey,
                        outcomeKey,  // Добавлен недостающий параметр
                        outcomeKey,
                        odds,
                        true
                ));
            }

            if (!mainOutcomes.isEmpty()) {
                // Используем правильный конструктор с 7 параметрами
                markets.add(new RawMarket(
                        "MAIN",       // marketTypeName
                        null,         // periodName
                        null,         // line (как String)
                        externalEventId, // externalId
                        externalEventId, // sourceExternalId
                        null,         // line (как BigDecimal)
                        mainOutcomes  // outcomes
                ));
            }

            // Извлекаем тоталы
            List<ElementHandle> totalsRows = page.querySelectorAll(".market-group:has(.market-title:has-text('Тотал'))");
            if (!totalsRows.isEmpty()) {
                for (ElementHandle totalsGroup : totalsRows) {
                    String lineText = totalsGroup.querySelector(".market-title").textContent().trim();
                    Matcher matcher = Pattern.compile("Тотал (\\d+\\.?\\d*)").matcher(lineText);
                    BigDecimal line = null;

                    if (matcher.find()) {
                        line = new BigDecimal(matcher.group(1));
                    }

                    List<RawOutcome> totalsOutcomes = new ArrayList<>();
                    List<ElementHandle> outcomeRows = totalsGroup.querySelectorAll(".outcome-row");

                    for (ElementHandle row : outcomeRows) {
                        String outcomeKey = row.querySelector(".outcome-name").textContent().trim();
                        String oddsText = row.querySelector(".outcome-coefficient").textContent().trim();
                        BigDecimal odds = new BigDecimal(oddsText.replace(",", "."));

                        // ИСПРАВЛЕНО: добавлен недостающий параметр outcomeValueDescription
                        totalsOutcomes.add(new RawOutcome(
                                outcomeKey.contains("Больше") ? "OVER" : "UNDER",
                                outcomeKey,  // Добавлен недостающий параметр
                                outcomeKey,
                                odds,
                                true
                        ));
                    }

                    if (!totalsOutcomes.isEmpty()) {
                        // Используем правильный конструктор с 7 параметрами
                        markets.add(new RawMarket(
                                "TOTAL",      // marketTypeName
                                null,         // periodName
                                line != null ? line.toString() : null, // line как String
                                externalEventId, // externalId
                                externalEventId, // sourceExternalId
                                line,         // line как BigDecimal
                                totalsOutcomes
                        ));
                    }
                }
            }

            browser.close();
        } catch (Exception e) {
            log.error("Error fetching markets for event {} from {}", externalEventId, code(), e);
        }

        log.info("Found {} markets for event {}", markets.size(), externalEventId);
        return markets;
    }

    private LocalDateTime parseMatchTime(String timeText) {
        // Реализация парсинга времени матча
        // Например, для формата "20:30" или "14 May 01:30"
        return LocalDateTime.now().plusHours(2); // Заглушка
    }
}