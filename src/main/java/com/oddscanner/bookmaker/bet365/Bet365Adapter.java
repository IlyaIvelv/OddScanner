// Bet365Adapter.java
package com.oddscanner.bookmaker.bet365;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(4)
public class Bet365Adapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(Bet365Adapter.class);

    private static final String BASE_URL = "https://www.bet365.com";
    private static final String LIVE_URL = "/#/live/";
    private static final String SPORTS_URL = "/#/sports/";

    // Популярные виды спорта для парсинга
    private static final List<String> SPORTS_TO_PARSE = Arrays.asList(
            "football", "tennis", "basketball", "ice-hockey"
    );

    private static final int THREAD_POOL_SIZE = 4;
    private static final int PAGE_TIMEOUT_MS = 30000;

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

    @Override
    public String code() {
        return "bet365";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА BET365 (Playwright) ===");

        List<RawEvent> allEvents = Collections.synchronizedList(new ArrayList<>());

        // Параллельно парсим разные виды спорта
        List<CompletableFuture<List<RawEvent>>> futures = new ArrayList<>();

        for (String sport : SPORTS_TO_PARSE) {
            futures.add(CompletableFuture.supplyAsync(() -> fetchEventsBySport(sport), executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.MINUTES);

            for (CompletableFuture<List<RawEvent>> future : futures) {
                allEvents.addAll(future.get());
            }
        } catch (Exception e) {
            log.error("Ошибка при парсинге Bet365: {}", e.getMessage());
        }

        log.info("=== BET365: ВСЕГО спаршено {} событий ===", allEvents.size());
        return allEvents;
    }

    private List<RawEvent> fetchEventsBySport(String sport) {
        List<RawEvent> events = new ArrayList<>();

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(false)
                     .setSlowMo(1000)
                     .setArgs(List.of(
                             "--no-sandbox",
                             "--disable-dev-shm-usage",
                             "--disable-blink-features=AutomationControlled"
                     )))) {

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .setViewportSize(1920, 1080)
                    .setLocale("en-GB")
                    .setTimezoneId("Europe/London"));

            Page page = context.newPage();

            // Переход на страницу спорта
            String url = BASE_URL + SPORTS_URL + sport + "/";
            log.debug("Bet365: переход на {}", url);

            page.navigate(url);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            // Закрываем модальные окна
            closeModals(page);

            // Парсим события
            events = parseEventsFromPage(page, sport);

            // Если не нашли события на прематч странице, пробуем live
            if (events.isEmpty()) {
                log.debug("Bet365: не найдено прематч событий для {}, пробуем live", sport);
                page.navigate(BASE_URL + LIVE_URL);
                page.waitForLoadState(LoadState.NETWORKIDLE);
                page.waitForTimeout(3000);
                events = parseEventsFromPage(page, sport);
            }

        } catch (Exception e) {
            log.error("Ошибка парсинга Bet365 для {}: {}", sport, e.getMessage());
        }

        return events;
    }

    private void closeModals(Page page) {
        try {
            // Закрываем поп-ап с согласием на куки
            ElementHandle acceptBtn = page.querySelector("button[data-testid='accept-cookies'], button:has-text('Accept'), button:has-text('I agree')");
            if (acceptBtn != null && acceptBtn.isVisible()) {
                acceptBtn.click();
                page.waitForTimeout(1000);
            }
        } catch (Exception e) {
            // Игнорируем
        }

        try {
            // Закрываем модалку с регистрацией/логином
            ElementHandle closeBtn = page.querySelector("button[aria-label='Close'], .close-modal, svg[aria-label='Close']");
            if (closeBtn != null && closeBtn.isVisible()) {
                closeBtn.click();
                page.waitForTimeout(500);
            }
        } catch (Exception e) {
            // Игнорируем
        }
    }

    private List<RawEvent> parseEventsFromPage(Page page, String sportName) {
        List<RawEvent> events = new ArrayList<>();

        try {
            // Ждём загрузки событий
            page.waitForSelector(".event-cell, .event-card, [data-testid='event-container']",
                    new Page.WaitForSelectorOptions().setTimeout(PAGE_TIMEOUT_MS));

            // Находим все карточки событий
            List<ElementHandle> eventCards = page.querySelectorAll(".event-cell, .event-card, [data-testid='event-card'], .event-row");

            log.debug("Bet365: найдено {} карточек событий для {}", eventCards.size(), sportName);

            for (ElementHandle card : eventCards) {
                try {
                    RawEvent event = parseEventCard(card, sportName);
                    if (event != null && event.getHomeTeamName() != null && event.getAwayTeamName() != null) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    log.debug("Ошибка парсинга карточки события: {}", e.getMessage());
                }
            }

        } catch (Exception e) {
            log.debug("Не удалось найти события на странице: {}", e.getMessage());
        }

        return events;
    }

    private RawEvent parseEventCard(ElementHandle card, String sportName) {
        try {
            // Парсим названия команд
            String homeTeam = "";
            String awayTeam = "";

            // Способ 1: через .team-name классы
            ElementHandle homeEl = card.querySelector(".team-name-home, .home-team, .team-home, .participant-home");
            ElementHandle awayEl = card.querySelector(".team-name-away, .away-team, .team-away, .participant-away");

            if (homeEl != null && awayEl != null) {
                homeTeam = cleanTeamName(homeEl.textContent());
                awayTeam = cleanTeamName(awayEl.textContent());
            }

            // Способ 2: через .memberName
            if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
                List<ElementHandle> members = card.querySelectorAll(".memberName, .member-name, .participant-name");
                if (members.size() >= 2) {
                    homeTeam = cleanTeamName(members.get(0).textContent());
                    awayTeam = cleanTeamName(members.get(1).textContent());
                }
            }

            // Способ 3: через текст внутри .event-name
            if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
                ElementHandle nameEl = card.querySelector(".event-name, .match-name, .title");
                if (nameEl != null) {
                    String name = nameEl.textContent();
                    String[] parts = name.split(" v ");
                    if (parts.length == 2) {
                        homeTeam = cleanTeamName(parts[0]);
                        awayTeam = cleanTeamName(parts[1]);
                    }
                }
            }

            if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
                return null;
            }

            // Парсим коэффициенты (кнопки с ценами)
            List<ElementHandle> priceButtons = card.querySelectorAll(".price, .odds-button, [data-testid='odds-button'], .bet-button");

            double odds1 = 0.0;
            double oddsX = 0.0;
            double odds2 = 0.0;

            for (ElementHandle btn : priceButtons) {
                String priceText = btn.textContent().trim();
                double odds = parseOdds(priceText);

                if (odds > 0) {
                    if (odds1 == 0.0) {
                        odds1 = odds;
                    } else if (oddsX == 0.0) {
                        oddsX = odds;
                    } else if (odds2 == 0.0) {
                        odds2 = odds;
                    }
                }
            }

            if (odds1 == 0.0 && oddsX == 0.0 && odds2 == 0.0) {
                return null;
            }

            // Генерируем ID события
            String eventId = "bet365_" + sportName + "_" + homeTeam.hashCode() + "_" + awayTeam.hashCode();
            String marketId = eventId + "_1X2";

            // Создаём рынок 1X2
            List<RawOutcome> outcomes = new ArrayList<>();

            if (odds1 > 0) {
                outcomes.add(RawOutcome.builder()
                        .externalMarketId(marketId)
                        .outcomeKeyName("HOME_WIN")
                        .outcomeValueDescription(homeTeam)
                        .odds(BigDecimal.valueOf(odds1))
                        .isActive(true)
                        .build());
            }

            if (oddsX > 0) {
                outcomes.add(RawOutcome.builder()
                        .externalMarketId(marketId)
                        .outcomeKeyName("DRAW")
                        .outcomeValueDescription("Ничья")
                        .odds(BigDecimal.valueOf(oddsX))
                        .isActive(true)
                        .build());
            }

            if (odds2 > 0) {
                outcomes.add(RawOutcome.builder()
                        .externalMarketId(marketId)
                        .outcomeKeyName("AWAY_WIN")
                        .outcomeValueDescription(awayTeam)
                        .odds(BigDecimal.valueOf(odds2))
                        .isActive(true)
                        .build());
            }

            RawMarket market = RawMarket.builder()
                    .externalId(marketId)
                    .externalEventId(eventId)
                    .marketTypeName("WIN_DRAW_WIN")
                    .periodName("FULL_TIME")
                    .outcomes(outcomes)
                    .build();

            RawEvent event = new RawEvent();
            event.setExternalId(eventId);
            event.setHomeTeamName(homeTeam);
            event.setAwayTeamName(awayTeam);
            event.setStartTime(LocalDateTime.now().plusHours(2)); // Bet365 не показывает время просто так
            event.setLeagueName("Unknown League");
            event.setSportName(mapSportName(sportName));
            event.setEventUrl(BASE_URL);
            event.setMarkets(List.of(market));

            log.debug("Bet365: найдено событие {} vs {} ({} | {})",
                    homeTeam, awayTeam, odds1, oddsX, odds2);

            return event;

        } catch (Exception e) {
            log.debug("Ошибка парсинга события Bet365: {}", e.getMessage());
            return null;
        }
    }

    private double parseOdds(String text) {
        try {
            // Очищаем текст от лишних символов
            String cleaned = text.replaceAll("[^0-9.,]", "").replace(",", ".");
            double odds = Double.parseDouble(cleaned);
            return odds > 1.0 ? odds : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private String cleanTeamName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "");
    }

    private String mapSportName(String sport) {
        switch (sport.toLowerCase()) {
            case "football": return "Футбол";
            case "tennis": return "Теннис";
            case "basketball": return "Баскетбол";
            case "ice-hockey": return "Хоккей";
            default: return "Спорт";
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}