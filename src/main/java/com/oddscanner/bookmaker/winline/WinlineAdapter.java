package com.oddscanner.bookmaker.winline;

import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

@Component
@Order(5)
public class WinlineAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(WinlineAdapter.class);

    private static final String BASE_URL = "https://winline.ru";
    private static final String FOOTBALL_URL = "/stavki/sport/futbol/";

    // Ручной список стран
    private static final List<String> FALLBACK_COUNTRIES = Arrays.asList(
            "rossiya", "angliya", "ispaniya", "germaniya", "italiya", "frantsiya",
            "portugaliya", "turetsiya", "niderlandyi", "belgiya", "greciya", "shotlandiya"
    );

    @Override
    public String code() {
        return "winline";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА WINLINE (многопоточный) ===");

        List<String> countries = Arrays.asList(
                "rossiya", "angliya", "ispaniya", "germaniya", "italiya",
                "portugaliya", "frantsiya", "turetsiya", "niderlandyi", "belgiya"
        );

        List<RawEvent> allEvents = Collections.synchronizedList(new ArrayList<>());
        ExecutorService executor = Executors.newFixedThreadPool(6);
        List<Future<List<RawEvent>>> futures = new ArrayList<>();

        for (String country : countries) {
            String countryUrl = "/stavki/sport/futbol/" + country;
            futures.add(executor.submit(() -> parseCountry(countryUrl)));
        }

        for (Future<List<RawEvent>> future : futures) {
            try {
                List<RawEvent> events = future.get(120, TimeUnit.SECONDS);
                allEvents.addAll(events);
            } catch (TimeoutException e) {
                log.error("Таймаут при парсинге страны");
            } catch (Exception e) {
                log.error("Ошибка: {}", e.getMessage());
            }
        }

        executor.shutdown();

        log.info("=== WINLINE: ВСЕГО спаршено {} событий ===", allEvents.size());
        return allEvents;
    }



    private List<RawEvent> parseCountry(String countryUrl) {
        List<RawEvent> events = new ArrayList<>();

        boolean isSlowCountry = countryUrl.contains("angliya") || countryUrl.contains("ispaniya") ||
                countryUrl.contains("germaniya") || countryUrl.contains("italiya");
        int timeoutSeconds = isSlowCountry ? 90000 : 60000;

        for (int attempt = 1; attempt <= 2; attempt++) {
            try (Playwright playwright = Playwright.create();
                 Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                         .setHeadless(false)
                         .setSlowMo(100)
                         .setArgs(List.of(
                                 "--no-sandbox",
                                 "--disable-dev-shm-usage",
                                 "--window-position=-3000,0",  // Окно за пределами экрана
                                 "--window-size=1280,720"
                         )))) {

                Page page = browser.newPage();
                String fullUrl = BASE_URL + countryUrl;

                log.info("🌍 Открываем страну: {} (попытка {})", fullUrl, attempt);
                page.navigate(fullUrl, new Page.NavigateOptions().setTimeout(timeoutSeconds));

                // Проверка на редирект на главную
                String currentUrl = page.url();
                if (currentUrl.equals(BASE_URL + "/") || currentUrl.equals(BASE_URL)) {
                    log.warn("🚫 Страна {} редиректнула на главную, пропускаем", countryUrl);
                    return events;
                }

                page.waitForLoadState(LoadState.NETWORKIDLE);
                Thread.sleep(300);

                // Скроллим для подгрузки контента
                for (int i = 0; i < 3; i++) {
                    page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
                    Thread.sleep(300);
                    page.evaluate("window.scrollTo(0, 0)");
                    Thread.sleep(200);
                }

                try {
                    page.waitForSelector(".event-card", new Page.WaitForSelectorOptions().setTimeout(30000));
                } catch (Exception e) {
                    log.warn("⚠️ Не дождались .event-card для {}", countryUrl);
                }

                Thread.sleep(2000);

                List<ElementHandle> eventCards = page.querySelectorAll(".event-card");
                log.info("📊 Найдено карточек: {}", eventCards.size());

                for (ElementHandle card : eventCards) {
                    RawEvent event = parseEventCard(card);
                    if (event != null) {
                        events.add(event);
                    }
                }

                if (!events.isEmpty()) {
                    break;
                }

            } catch (Exception e) {
                log.error("❌ Ошибка парсинга страны {} (попытка {}): {}", countryUrl, attempt, e.getMessage());
                if (attempt == 2) {
                    log.error("Страна {} не загрузилась после 2 попыток", countryUrl);
                }
            }
        }

        log.info("✅ Страна {}: {} событий", countryUrl, events.size());
        return events;
    }


    private RawEvent parseEventCard(ElementHandle card) {
        try {
            List<ElementHandle> nameElements = card.querySelectorAll(".name");
            if (nameElements.size() < 3) return null;

            String homeTeam = nameElements.get(1).innerText().trim();
            String awayTeam = nameElements.get(2).innerText().trim();

            String cardText = card.textContent();
            Pattern pattern = Pattern.compile("(\\d+\\.\\d{2})");
            java.util.regex.Matcher matcher = pattern.matcher(cardText);

            List<Double> odds = new ArrayList<>();
            while (matcher.find() && odds.size() < 3) {
                double val = Double.parseDouble(matcher.group(1));
                if (val >= 1.0 && val <= 20.0) odds.add(val);
            }

            if (odds.size() < 3) return null;

            String eventId = "winline_" + System.currentTimeMillis() + "_" + Math.abs(homeTeam.hashCode());
            String marketId = eventId + "_1X2";

            List<RawOutcome> outcomes = new ArrayList<>();
            outcomes.add(RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("HOME_WIN").outcomeValueDescription(homeTeam).odds(BigDecimal.valueOf(odds.get(0))).isActive(true).build());
            outcomes.add(RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("DRAW").outcomeValueDescription("Ничья").odds(BigDecimal.valueOf(odds.get(1))).isActive(true).build());
            outcomes.add(RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("AWAY_WIN").outcomeValueDescription(awayTeam).odds(BigDecimal.valueOf(odds.get(2))).isActive(true).build());

            RawMarket market = RawMarket.builder().externalId(marketId).externalEventId(eventId).marketTypeName("WIN_DRAW_WIN").periodName("FULL_TIME").outcomes(outcomes).build();

            RawEvent event = new RawEvent();
            event.setExternalId(eventId);
            event.setHomeTeamName(homeTeam);
            event.setAwayTeamName(awayTeam);
            event.setStartTime(LocalDateTime.now().plusHours(2));
            event.setLeagueName("Футбол");
            event.setSportName("Футбол");
            event.setMarkets(List.of(market));

            return event;

        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}