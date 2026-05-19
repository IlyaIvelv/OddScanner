package com.oddscanner.bookmaker.Betcity;

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

@Component
@Order(3)
public class BetcityParser implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(BetcityParser.class);

    private static final String BASE_URL = "https://betcity.ru";
    private static final String LINE_URL = "/ru/line/";

    @Override
    public String code() {
        return "betcity";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА BETCITY (многопоточный, ускоренный) ===");
        List<RawEvent> allEvents = Collections.synchronizedList(new ArrayList<>());

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")))) {

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));

            Page page = context.newPage();

            // ШАГ 1: Находим все URL турниров
            List<String> allTournamentUrls = findTournamentUrls(page);
            log.info("Найдено всего турниров: {}", allTournamentUrls.size());

            // ШАГ 2: Фильтруем популярные (увеличено до 200)
            List<String> popularUrls = filterPopularTournaments(allTournamentUrls);
            log.info("Отобрано популярных турниров: {}", popularUrls.size());

            // ШАГ 3: Многопоточный парсинг (увеличено до 10 потоков)
            ExecutorService executor = Executors.newFixedThreadPool(15);
            List<Future<List<RawEvent>>> futures = new ArrayList<>();

            for (String tournamentUrl : popularUrls) {
                Future<List<RawEvent>> future = executor.submit(() -> {
                    log.info("Парсинг турнира в потоке: {}", tournamentUrl);
                    return parseTournamentStandalone(tournamentUrl);
                });
                futures.add(future);
            }

            // Собираем результаты
            for (Future<List<RawEvent>> future : futures) {
                try {
                    List<RawEvent> events = future.get(45, TimeUnit.SECONDS);
                    allEvents.addAll(events);
                    log.info("Добавлено {} событий из турнира", events.size());
                } catch (TimeoutException e) {
                    log.error("Таймаут при парсинге турнира");
                } catch (Exception e) {
                    log.error("Ошибка при получении результатов: {}", e.getMessage());
                }
            }

            executor.shutdown();
            try {
                executor.awaitTermination(60, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                log.error("Прерывание ожидания потоков");
            }

        } catch (Exception e) {
            log.error("Ошибка: {}", e.getMessage());
        }

        log.info("=== BETCITY: ВСЕГО спаршено {} событий ===", allEvents.size());
        return allEvents;
    }

    private List<String> findTournamentUrls(Page page) {
        List<String> urls = new ArrayList<>();

        try {
            log.info("Поиск турниров на главной странице...");
            page.navigate(BASE_URL + LINE_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            List<ElementHandle> links = page.querySelectorAll("a");

            for (ElementHandle link : links) {
                try {
                    String href = link.getAttribute("href");
                    if (href != null && href.startsWith("/ru/line/") && !href.equals("/ru/line/")) {
                        if (href.split("/").length <= 5 && !href.contains("?ext")) {
                            if (!urls.contains(href)) {
                                urls.add(href);
                                log.debug("Найден турнирный URL: {}", href);
                            }
                        }
                    }
                } catch (Exception e) {
                    // ignore
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при поиске турниров: {}", e.getMessage());
        }

        return urls;
    }



    private List<RawEvent> parseTournamentStandalone(String tournamentUrl) {
        List<RawEvent> events = new ArrayList<>();

        try (Playwright playwright = Playwright.create();
             Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                     .setHeadless(true)
                     .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")))) {

            BrowserContext context = browser.newContext(new Browser.NewContextOptions()
                    .setUserAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"));

            Page page = context.newPage();

            String fullUrl = BASE_URL + tournamentUrl;
            page.navigate(fullUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(2000);

            List<ElementHandle> eventElements = page.querySelectorAll(".line-event");
            String sportName = extractSportName(tournamentUrl);

            for (ElementHandle eventElement : eventElements) {
                RawEvent rawEvent = parseEvent(eventElement, sportName);
                if (rawEvent != null && rawEvent.getHomeTeamName() != null && rawEvent.getAwayTeamName() != null) {
                    events.add(rawEvent);
                }
            }

        } catch (Exception e) {
            log.error("Ошибка при парсинге турнира {}: {}", tournamentUrl, e.getMessage());
        }

        return events;
    }

    private List<String> filterPopularTournaments(List<String> allUrls) {
        List<String> popularUrls = new ArrayList<>();

        // Приоритетные топ-турниры (футбол + хоккей + баскетбол + теннис)
        List<String> priorityTournaments = List.of(
                // Футбол
                "/soccer/445",        // АПЛ
                "/soccer/836866",     // Лига чемпионов
                "/soccer/70908",      // Ла Лига?
                "/soccer/72092",      // Бундеслига?
                "/soccer/144905",     // Серия А?
                "/soccer/797625",     // Лига 1?
                // Хоккей
                "/ice-hockey/1423",   // КХЛ
                "/ice-hockey/11759",  // НХЛ
                // Баскетбол
                "/basketball/1472",   // НБА
                "/basketball/325",    // Евролига
                // Теннис
                "/tennis/1434",       // ATP
                "/tennis/1482"        // WTA
        );

        // Сначала добавляем приоритетные
        for (String pattern : priorityTournaments) {
            for (String url : allUrls) {
                if (url.contains(pattern) && !popularUrls.contains(url)) {
                    popularUrls.add(url);
                    log.info("Добавлен приоритетный турнир: {}", url);
                    break;
                }
            }
        }

        // Затем добавляем ВСЕ турниры с ID (без ?ext=1)
        for (String url : allUrls) {
            if (popularUrls.size() >= 300) break;  // Увеличили до 300
            if (popularUrls.contains(url)) continue;

            // Берём любые URL вида /ru/line/SPORT/ЧИСЛО
            if (url.matches("/ru/line/[a-z-]+/\\d+") && !url.contains("?")) {
                popularUrls.add(url);
                log.debug("Добавлен турнир: {}", url);
            }
        }

        log.info("Отобрано турниров: {} (лимит 300)", popularUrls.size());
        return popularUrls;
    }
    private RawEvent parseEvent(ElementHandle event, String sportName) {
        try {
            ElementHandle nameLink = event.querySelector(".line-event__name");
            if (nameLink == null) {
                return null;
            }

            String fullText = nameLink.innerText().trim();
            String[] lines = fullText.split("\\r?\\n");
            String homeTeam = "";
            String awayTeam = "";

            for (String line : lines) {
                line = line.trim();
                if (!line.isEmpty() && !line.contains(":") && !line.matches(".*\\d+.*")) {
                    if (homeTeam.isEmpty()) {
                        homeTeam = cleanTeamName(line);
                    } else if (awayTeam.isEmpty() && !line.equals(homeTeam)) {
                        awayTeam = cleanTeamName(line);
                        break;
                    }
                }
            }

            if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
                return null;
            }

            List<ElementHandle> allButtons = event.querySelectorAll("button");
            double homeWin = 0.0;
            double draw = 0.0;
            double awayWin = 0.0;
            int oddIndex = 0;

            for (ElementHandle btn : allButtons) {
                String btnText = btn.innerText().trim();
                if (btnText.matches("\\d+\\.\\d{2}") || btnText.matches("\\d+\\.\\d{1}")) {
                    try {
                        double value = Double.parseDouble(btnText);
                        if (value >= 1.0 && value <= 100.0) {
                            if (oddIndex == 0) {
                                homeWin = value;
                            } else if (oddIndex == 1) {
                                draw = value;
                            } else if (oddIndex == 2) {
                                awayWin = value;
                            } else {
                                break;
                            }
                            oddIndex++;
                        }
                    } catch (NumberFormatException e) {
                        // ignore
                    }
                }
            }

            if (homeWin == 0.0 && draw == 0.0 && awayWin == 0.0) {
                return null;
            }

            String eventId = "betcity_" + sportName + "_" + homeTeam.hashCode() + "_" + awayTeam.hashCode();
            String marketId = eventId + "_1x2";

            List<RawOutcome> outcomes = List.of(
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("1").outcomeValueDescription(homeTeam).odds(BigDecimal.valueOf(homeWin)).isActive(homeWin > 0).build(),
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("X").outcomeValueDescription("Ничья").odds(BigDecimal.valueOf(draw)).isActive(draw > 0).build(),
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("2").outcomeValueDescription(awayTeam).odds(BigDecimal.valueOf(awayWin)).isActive(awayWin > 0).build()
            );

            RawMarket market = RawMarket.builder()
                    .externalId(marketId)
                    .externalEventId(eventId)
                    .marketTypeName("WIN_DRAW_WIN")
                    .periodName("FULL_TIME")
                    .line(null)
                    .outcomes(outcomes)
                    .build();

            RawEvent rawEvent = new RawEvent();
            rawEvent.setExternalId(eventId);
            rawEvent.setHomeTeamName(homeTeam);
            rawEvent.setAwayTeamName(awayTeam);
            rawEvent.setStartTime(LocalDateTime.now().plusHours(2));
            rawEvent.setLeagueName(sportName);
            rawEvent.setSportName(sportName);
            rawEvent.setEventUrl(BASE_URL + LINE_URL);
            rawEvent.setMarkets(List.of(market));

            return rawEvent;

        } catch (Exception e) {
            log.debug("Ошибка парсинга события: {}", e.getMessage());
            return null;
        }
    }

    private String extractSportName(String tournamentUrl) {
        if (tournamentUrl.contains("/soccer/")) return "Футбол";
        if (tournamentUrl.contains("/ice-hockey/") || tournamentUrl.contains("/hockey/")) return "Хоккей";
        if (tournamentUrl.contains("/basketball/")) return "Баскетбол";
        if (tournamentUrl.contains("/tennis/")) return "Теннис";
        if (tournamentUrl.contains("/volleyball/")) return "Волейбол";
        if (tournamentUrl.contains("/cybersport/")) return "Киберспорт";
        if (tournamentUrl.contains("/boxing/")) return "Бокс";
        if (tournamentUrl.contains("/ufc/")) return "MMA";
        if (tournamentUrl.contains("/handball/")) return "Гандбол";
        if (tournamentUrl.contains("/baseball/")) return "Бейсбол";
        return "Спорт";
    }

    private String cleanTeamName(String name) {
        if (name == null) return null;
        return name.replaceAll("\\([^)]*\\)", "").replaceAll("\\s+", " ").trim();
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}