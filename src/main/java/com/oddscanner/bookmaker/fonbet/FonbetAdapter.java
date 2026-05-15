package com.oddscanner.bookmaker.fonbet;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.options.LoadState;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

@Component
public class FonbetAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FonbetAdapter.class);
    private static final String BOOKMAKER_NAME = "fonbet";
    private static final String URL_LIVE = "https://fon.bet/live/football";

    // CSS селекторы с использованием частичного совпадения (динамические классы)
    private static final String EVENT_CONTAINER_SELECTOR = "[class*='sport-base-event-wrap']";
    private static final String EVENT_MAIN_SELECTOR = "[class*='sport-base-event--']";
    private static final String TEAM_NAME_SELECTOR = "[class*='sport-base-event__main__caption__rendertron']";
    private static final String SCORE_SELECTOR = "[class*='event-block-score--']";
    private static final String TIME_SELECTOR = "[class*='event-block-current-time__time--']";
    private static final String FACTOR_CONTAINER_SELECTOR = "[class*='factor-value--']";
    private static final String FACTOR_VALUE_SELECTOR = "[class*='value--']";
    private static final String FACTOR_PARAM_SELECTOR = "[class*='param--']";

    @Override
    public String code() {
        return BOOKMAKER_NAME;
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("Starting to fetch events from Fonbet");

        Page page = null;
        com.microsoft.playwright.Playwright playwright = null;
        com.microsoft.playwright.Browser browser = null;

        try {
            playwright = com.microsoft.playwright.Playwright.create();
            browser = playwright.chromium().launch(
                    new com.microsoft.playwright.BrowserType.LaunchOptions().setHeadless(false) // Для отладки лучше false
            );
            page = browser.newPage();

            page.navigate(URL_LIVE);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            Thread.sleep(5000); // Даем время для полной загрузки динамического контента

            savePageSource(page);

            // Ждем появления событий
            page.waitForSelector(EVENT_CONTAINER_SELECTOR, new Page.WaitForSelectorOptions().setTimeout(30000));

            List<ElementHandle> eventContainers = page.querySelectorAll(EVENT_CONTAINER_SELECTOR);
            log.info("Found {} event containers", eventContainers.size());

            List<RawEvent> events = new ArrayList<>();

            for (ElementHandle container : eventContainers) {
                try {
                    RawEvent event = parseEvent(container);
                    if (event != null && event.getMarkets() != null && !event.getMarkets().isEmpty()) {
                        events.add(event);
                        log.info("✓ Parsed: {} | Score: {}:{}",
                                event.getName(), event.getHomeScore(), event.getAwayScore());
                    }
                } catch (Exception e) {
                    log.warn("Failed to parse event: {}", e.getMessage());
                }
            }

            log.info("Successfully parsed {} events from Fonbet", events.size());
            return events;

        } catch (Exception e) {
            log.error("Failed to fetch events from Fonbet", e);
            return Collections.emptyList();
        } finally {
            if (page != null && !page.isClosed()) page.close();
            if (browser != null) browser.close();
            if (playwright != null) playwright.close();
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String eventId) {
        return Collections.emptyList();
    }

    private RawEvent parseEvent(ElementHandle container) {
        // Ищем основной блок события
        ElementHandle mainBlock = container.querySelector(EVENT_MAIN_SELECTOR);
        if (mainBlock == null) {
            log.debug("Main block not found");
            return null;
        }

        // Извлекаем название матча
        ElementHandle teamNameElement = mainBlock.querySelector(TEAM_NAME_SELECTOR);
        if (teamNameElement == null) {
            log.debug("Team name element not found");
            return null;
        }

        String matchName = teamNameElement.textContent().trim();
        if (matchName.isEmpty()) return null;

        String[] teams = splitTeams(matchName);
        String homeTeam = teams[0];
        String awayTeam = teams[1];

        // Извлекаем счет
        String scoreText = "0:0";
        ElementHandle scoreElement = mainBlock.querySelector(SCORE_SELECTOR);
        if (scoreElement != null && scoreElement.textContent() != null) {
            scoreText = scoreElement.textContent().trim();
        }
        int[] scores = parseScore(scoreText);

        // Извлекаем время
        String time = "";
        ElementHandle timeElement = mainBlock.querySelector(TIME_SELECTOR);
        if (timeElement != null && timeElement.textContent() != null) {
            time = timeElement.textContent().trim();
        }

        // Извлекаем коэффициенты
        List<RawMarket> markets = extractMarkets(mainBlock);

        if (markets.isEmpty()) return null;

        RawEvent event = new RawEvent();
        event.setBookmakerId(BOOKMAKER_NAME);
        event.setName(matchName);
        event.setHomeTeam(homeTeam);
        event.setAwayTeam(awayTeam);
        event.setHomeScore(scores[0]);
        event.setAwayScore(scores[1]);
        event.setEventTime(time);
        event.setMarkets(markets);
        event.setUpdatedAt(LocalDateTime.now());

        return event;
    }

    private List<RawMarket> extractMarkets(ElementHandle mainBlock) {
        List<RawMarket> markets = new ArrayList<>();

        // Находим все блоки с коэффициентами
        List<ElementHandle> factorBlocks = mainBlock.querySelectorAll(FACTOR_CONTAINER_SELECTOR);
        log.debug("Found {} factor blocks", factorBlocks.size());

        if (factorBlocks.isEmpty()) return markets;

        // Рынок исходов (первые 3 коэффициента - это 1, X, 2)
        RawMarket matchWinnerMarket = new RawMarket();
        matchWinnerMarket.setType("MATCH_WINNER");
        matchWinnerMarket.setId(UUID.randomUUID().toString());

        List<RawOutcome> outcomes = new ArrayList<>();
        String[] selectionNames = {"1", "X", "2"};

        for (int i = 0; i < Math.min(3, factorBlocks.size()); i++) {
            ElementHandle factorBlock = factorBlocks.get(i);
            RawOutcome outcome = parseOutcome(factorBlock);

            if (outcome != null && outcome.getValue() > 0) {
                outcome.setName(selectionNames[i]);
                outcome.setId(UUID.randomUUID().toString());
                outcomes.add(outcome);
                log.debug("  Outcome {}: {}", selectionNames[i], outcome.getValue());
            }
        }

        if (!outcomes.isEmpty()) {
            matchWinnerMarket.setOutcomes(outcomes);
            markets.add(matchWinnerMarket);
        }

        return markets;
    }

    private RawOutcome parseOutcome(ElementHandle factorBlock) {
        try {
            ElementHandle valueElement = factorBlock.querySelector(FACTOR_VALUE_SELECTOR);
            if (valueElement == null) {
                // Пробуем альтернативный селектор внутри блока
                valueElement = factorBlock.querySelector("[class*='value']");
                if (valueElement == null) return null;
            }

            String valueStr = valueElement.textContent().trim();
            log.debug("  Raw value string: '{}'", valueStr);

            if (valueStr.equals("-") || valueStr.isEmpty()) return null;

            // Очищаем от лишних символов и пробелов
            valueStr = valueStr.replaceAll("[^\\d.]", "");
            if (valueStr.isEmpty()) return null;

            double oddValue;
            try {
                oddValue = Double.parseDouble(valueStr);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse value: {}", valueStr);
                return null;
            }

            if (oddValue <= 0) return null;

            RawOutcome outcome = new RawOutcome();
            outcome.setValue(oddValue);

            return outcome;
        } catch (Exception e) {
            log.debug("Error parsing outcome: {}", e.getMessage());
            return null;
        }
    }

    private String[] splitTeams(String matchName) {
        String[] parts = matchName.split(" - ");
        if (parts.length == 2) {
            return new String[]{parts[0].trim(), parts[1].trim()};
        }
        // Если разделитель другой (например, " vs ")
        parts = matchName.split(" vs ");
        if (parts.length == 2) {
            return new String[]{parts[0].trim(), parts[1].trim()};
        }
        return new String[]{matchName, ""};
    }

    private int[] parseScore(String scoreText) {
        if (scoreText == null || scoreText.isEmpty()) return new int[]{0, 0};
        // Очищаем от возможных комментариев в скобках (например, "0:0 (0-0)")
        scoreText = scoreText.split("\\(")[0].trim();
        String[] parts = scoreText.split(":");
        if (parts.length != 2) return new int[]{0, 0};
        try {
            return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException e) {
            return new int[]{0, 0};
        }
    }

    private void savePageSource(Page page) {
        try {
            String content = page.content();
            Path path = Paths.get("fonbet_debug.html");
            Files.writeString(path, content);
            log.info("Saved page source to {}", path.toAbsolutePath());
        } catch (Exception e) {
            log.warn("Failed to save page source: {}", e.getMessage());
        }
    }
}