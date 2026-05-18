package com.oddscanner.bookmaker.marathon;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(1)
public class MarathonAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);
    private static final String BASE_URL = "https://www.marathonbet.ru";

    // Расширенный список лиг
    private static final List<String> FOOTBALL_URLS = Arrays.asList(
            // Топ-5 лиг
            "/su/betting/Football/England/Premier-League/",
            "/su/betting/Football/Spain/Primera-Division/",
            "/su/betting/Football/Italy/Serie-A/",
            "/su/betting/Football/Germany/Bundesliga/",
            "/su/betting/Football/France/Ligue-1/",

            // Россия и Европа
            "/su/betting/Football/Russia/Premier-League/",
            "/su/betting/Football/Portugal/Primeira-Liga/",
            "/su/betting/Football/Netherlands/Eredivisie/",
            "/su/betting/Football/Turkey/Super-Lig/",
            "/su/betting/Football/Belgium/Pro-League/",

            // Южная Америка
            "/su/betting/Football/Brazil/Serie-A/",
            "/su/betting/Football/Argentina/Professional-Cup/",

            // Азия
            "/su/betting/Football/Japan/J1-League/",
            "/su/betting/Football/South-Korea/K-League-1/",
            "/su/betting/Football/China/CSL/",

            // Европейские кубки и сборные
            "/su/betting/Football/UEFA-Champions-League/",
            "/su/betting/Football/UEFA-Europa-League/",
            "/su/betting/Football/UEFA-Conference-League/",
            "/su/betting/Football/UEFA-Nations-League/",

            // Чемпионаты мира и Европы
            "/su/betting/Football/World-Cup/",
            "/su/betting/Football/European-Championship/",

            // Другие европейские лиги
            "/su/betting/Football/Poland/Ekstraklasa/",
            "/su/betting/Football/Czech-Republic/1-Liga/",
            "/su/betting/Football/Greece/Super-League/",
            "/su/betting/Football/Scotland/Premiership/",
            "/su/betting/Football/Switzerland/Super-League/",
            "/su/betting/Football/Austria/Bundesliga/"
    );

    private final Map<String, List<RawMarket>> marketsCache = new HashMap<>();

    private static final List<String> USER_AGENTS = Arrays.asList(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    );

    @Override
    public String code() {
        return "marathon";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== MARATHON fetchEvents() START ===");
        List<RawEvent> allEvents = new ArrayList<>();

        for (String url : FOOTBALL_URLS) {
            try {
                List<RawEvent> leagueEvents = fetchEventsForUrl(url);
                allEvents.addAll(leagueEvents);
                if (!leagueEvents.isEmpty()) {
                    log.info("✅ {}: {} событий", getLeagueNameFromUrl(url), leagueEvents.size());
                }
                Thread.sleep(2000); // пауза между лигами 2 секунды
            } catch (Exception e) {
                log.error("Ошибка при загрузке {}: {}", url, e.getMessage());
            }
        }

        // Удаляем дубликаты по externalId
        Map<String, RawEvent> uniqueEvents = new LinkedHashMap<>();
        for (RawEvent event : allEvents) {
            uniqueEvents.putIfAbsent(event.getExternalId(), event);
        }

        log.info("=== MARATHON ИТОГО: {} уникальных событий из {} лиг ===",
                uniqueEvents.size(), FOOTBALL_URLS.size());
        return new ArrayList<>(uniqueEvents.values());
    }

    private String getLeagueNameFromUrl(String url) {
        if (url.contains("Premier-League")) return "АПЛ";
        if (url.contains("Primera-Division")) return "Ла Лига";
        if (url.contains("Serie-A")) return "Серия А";
        if (url.contains("Bundesliga")) return "Бундеслига";
        if (url.contains("Ligue-1")) return "Лига 1";
        if (url.contains("Russia")) return "РПЛ";
        if (url.contains("Primeira-Liga")) return "Португалия";
        if (url.contains("Eredivisie")) return "Нидерланды";
        if (url.contains("Super-Lig")) return "Турция";
        if (url.contains("Brazil")) return "Бразилия";
        if (url.contains("Argentina")) return "Аргентина";
        if (url.contains("Champions-League")) return "Лига Чемпионов";
        if (url.contains("Europa-League")) return "Лига Европы";
        if (url.contains("Nations-League")) return "Лига Наций";
        return url.substring(url.lastIndexOf("/", url.length() - 2) + 1, url.length() - 1);
    }

    private List<RawEvent> fetchEventsForUrl(String footballUrl) {
        List<RawEvent> allEvents = new ArrayList<>();
        marketsCache.clear();

        Playwright playwright = null;
        Browser browser = null;
        Page page = null;
        BrowserContext context = null;

        try {
            playwright = Playwright.create();
            String userAgent = USER_AGENTS.get(new Random().nextInt(USER_AGENTS.size()));

            List<String> launchArgs = Arrays.asList(
                    "--no-sandbox",
                    "--disable-dev-shm-usage",
                    "--disable-blink-features=AutomationControlled"
            );

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setSlowMo(100)
                    .setArgs(launchArgs);

            browser = playwright.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent(userAgent)
                    .setViewportSize(1920, 1080)
                    .setLocale("ru-RU");

            context = browser.newContext(contextOptions);
            page = context.newPage();

            String fullUrl = BASE_URL + footballUrl;

            page.navigate(fullUrl, new Page.NavigateOptions().setTimeout(45000));
            page.evaluate("window.scrollBy(0, 300)");
            Thread.sleep(500);

            page.waitForLoadState(LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(30000));
            page.waitForTimeout(1500);

            try {
                page.waitForSelector(".bg.coupon-row, div[data-event-eventid]",
                        new Page.WaitForSelectorOptions().setTimeout(10000));
            } catch (TimeoutError e) {
                return Collections.emptyList();
            }

            String pageTitle = page.title();
            if (pageTitle.contains("Forbidden") || pageTitle.contains("Access Denied")) {
                return Collections.emptyList();
            }

            Map<String, String> leagueMap = buildLeagueMap(page);

            List<ElementHandle> eventRows = page.querySelectorAll(".bg.coupon-row, [class*='coupon-row']");
            if (eventRows.isEmpty()) {
                eventRows = page.querySelectorAll("div[data-event-eventid]");
            }

            if (eventRows.isEmpty()) {
                return Collections.emptyList();
            }

            Set<String> processedMatches = new HashSet<>();

            for (ElementHandle eventEl : eventRows) {
                try {
                    String eventId = eventEl.getAttribute("data-event-eventid");
                    if (eventId == null) eventId = eventEl.getAttribute("data-event-treeid");
                    if (eventId == null) eventId = UUID.randomUUID().toString();
                    if (eventId.length() > 20) continue;

                    List<ElementHandle> memberNames = eventEl.querySelectorAll(".member-name");
                    if (memberNames.size() < 2) continue;

                    String homeTeam = memberNames.get(0).textContent().trim();
                    String awayTeam = memberNames.get(1).textContent().trim();
                    if (homeTeam.isEmpty() || awayTeam.isEmpty()) continue;

                    String matchKey = homeTeam + " vs " + awayTeam;
                    if (processedMatches.contains(matchKey)) continue;
                    processedMatches.add(matchKey);

                    List<RawMarket> markets = parseMarketsFromEventRow(eventEl, eventId);
                    if (markets.isEmpty()) {
                        markets = parseMarketsAlternative(eventEl, eventId);
                    }

                    marketsCache.put(eventId, markets);

                    RawEvent rawEvent = new RawEvent();
                    rawEvent.setExternalId(eventId);
                    rawEvent.setHomeTeamName(homeTeam);
                    rawEvent.setAwayTeamName(awayTeam);
                    rawEvent.setStartTime(LocalDateTime.now().plusDays(7));
                    rawEvent.setLeagueName(extractLeagueName(eventEl, leagueMap));
                    rawEvent.setSportName("Football");
                    rawEvent.setEventUrl(BASE_URL + "/su/betting/Football/event/" + eventId);
                    rawEvent.setMarkets(markets);

                    allEvents.add(rawEvent);

                } catch (Exception e) {
                    // тихо пропускаем ошибки парсинга отдельных событий
                }
            }

        } catch (Exception e) {
            // тихо пропускаем ошибки загрузки лиги
        } finally {
            try { if (page != null) page.close(); } catch (Exception e) {}
            try { if (context != null) context.close(); } catch (Exception e) {}
            try { if (browser != null) browser.close(); } catch (Exception e) {}
            try { if (playwright != null) playwright.close(); } catch (Exception e) {}
        }

        return allEvents;
    }

    private Map<String, String> buildLeagueMap(Page page) {
        Map<String, String> leagueMap = new HashMap<>();
        try {
            List<ElementHandle> containers = page.querySelectorAll(".category-container");
            for (ElementHandle container : containers) {
                String categoryTreeId = container.getAttribute("data-category-treeid");
                if (categoryTreeId == null) continue;
                ElementHandle labelTd = container.querySelector(".category-label-td");
                if (labelTd == null) continue;
                ElementHandle leagueDiv = labelTd.querySelector("div");
                String leagueName = leagueDiv != null ? leagueDiv.textContent().trim() : labelTd.textContent().trim();
                if (!leagueName.isEmpty() && leagueName.length() < 100) {
                    leagueMap.put(categoryTreeId, leagueName);
                }
            }
        } catch (Exception e) {
            // тихо
        }
        return leagueMap;
    }

    private String extractLeagueName(ElementHandle eventRow, Map<String, String> leagueMap) {
        try {
            ElementHandle container = eventRow.querySelector("xpath=ancestor::div[contains(@class, 'category-container')]");
            if (container != null) {
                String categoryId = container.getAttribute("data-category-treeid");
                if (categoryId != null && leagueMap.containsKey(categoryId)) {
                    return leagueMap.get(categoryId);
                }
            }
        } catch (Exception e) {
            // тихо
        }
        return "Unknown League";
    }

    private List<RawMarket> parseMarketsAlternative(ElementHandle eventRow, String eventId) {
        List<RawMarket> markets = new ArrayList<>();
        try {
            List<ElementHandle> oddsElements = eventRow.querySelectorAll("[class*='price'], [class*='odds'], .selection-link");
            for (ElementHandle el : oddsElements) {
                String text = el.textContent().trim();
                BigDecimal odds = parseOdds(text);
                if (odds == null) continue;
                RawMarket market = new RawMarket();
                market.setExternalId(eventId + "_WIN_DRAW_WIN");
                market.setExternalEventId(eventId);
                market.setMarketTypeName("WIN_DRAW_WIN");
                market.setPeriodName("FULL_TIME");
                RawOutcome outcome = new RawOutcome();
                outcome.setExternalMarketId(market.getExternalId());
                outcome.setOutcomeKeyName("HOME_WIN");
                outcome.setOutcomeValueDescription("П1");
                outcome.setOdds(odds);
                outcome.setActive(true);
                market.setOutcomes(new ArrayList<>());
                market.getOutcomes().add(outcome);
                markets.add(market);
                break;
            }
        } catch (Exception e) {
            // тихо
        }
        return markets;
    }

    private List<RawMarket> parseMarketsFromEventRow(ElementHandle eventRow, String eventId) {
        List<RawMarket> markets = new ArrayList<>();
        List<ElementHandle> priceCells = eventRow.querySelectorAll("td.price");
        for (ElementHandle cell : priceCells) {
            try {
                String marketType = cell.getAttribute("data-market-type");
                if (marketType == null) continue;
                ElementHandle span = cell.querySelector("span.selection-link");
                if (span == null) continue;
                String priceText = span.textContent().trim();
                BigDecimal odds = parseOdds(priceText);
                if (odds == null) continue;
                String cellText = cell.textContent().trim();
                String lineValue = extractLineValue(cellText);
                String outcomeKey = determineOutcomeKey(cell, marketType);
                String outcomeDesc = determineOutcomeDescription(cell, marketType, lineValue);
                String mappedMarketType = mapMarketType(marketType);
                RawMarket market = findOrCreateMarket(markets, eventId, mappedMarketType, lineValue);
                RawOutcome outcome = new RawOutcome();
                outcome.setExternalMarketId(market.getExternalId());
                outcome.setOutcomeKeyName(outcomeKey);
                outcome.setOutcomeValueDescription(outcomeDesc);
                outcome.setOdds(odds);
                outcome.setActive(true);
                if (market.getOutcomes() == null) {
                    market.setOutcomes(new ArrayList<>());
                }
                market.getOutcomes().add(outcome);
            } catch (Exception e) {
                // тихо
            }
        }
        return markets;
    }

    private RawMarket findOrCreateMarket(List<RawMarket> markets, String eventId, String marketType, String lineValue) {
        String marketId = eventId + "_" + marketType + (lineValue != null ? "_" + lineValue : "");
        for (RawMarket m : markets) {
            if (m.getExternalId().equals(marketId)) {
                return m;
            }
        }
        RawMarket newMarket = new RawMarket();
        newMarket.setExternalId(marketId);
        newMarket.setExternalEventId(eventId);
        newMarket.setMarketTypeName(marketType);
        newMarket.setPeriodName("FULL_TIME");
        if (lineValue != null && !lineValue.isEmpty()) {
            try {
                newMarket.setLine(new BigDecimal(lineValue));
            } catch (Exception e) {}
        }
        markets.add(newMarket);
        return newMarket;
    }

    private String mapMarketType(String type) {
        switch (type) {
            case "RESULT": return "WIN_DRAW_WIN";
            case "DOUBLE_CHANCE": return "DOUBLE_CHANCE";
            case "HANDICAP": return "HANDICAP";
            case "TOTAL": return "TOTAL_GOALS";
            default: return type;
        }
    }

    private String determineOutcomeKey(ElementHandle cell, String marketType) {
        String cellHtml = cell.innerHTML().toLowerCase();
        switch (marketType) {
            case "RESULT":
                if (cellHtml.contains("match_result.1")) return "HOME_WIN";
                if (cellHtml.contains("match_result.draw")) return "DRAW";
                if (cellHtml.contains("match_result.3")) return "AWAY_WIN";
                break;
            case "DOUBLE_CHANCE":
                if (cellHtml.contains("result.hd")) return "HOME_OR_DRAW";
                if (cellHtml.contains("result.ha")) return "HOME_OR_AWAY";
                if (cellHtml.contains("result.ad")) return "DRAW_OR_AWAY";
                break;
            case "HANDICAP":
                if (cellHtml.contains("hb_h")) return "HOME_HANDICAP";
                if (cellHtml.contains("hb_a")) return "AWAY_HANDICAP";
                break;
            case "TOTAL":
                if (cellHtml.contains("under")) return "UNDER";
                if (cellHtml.contains("over")) return "OVER";
                break;
        }
        return "UNKNOWN";
    }

    private String determineOutcomeDescription(ElementHandle cell, String marketType, String lineValue) {
        String cellHtml = cell.innerHTML().toLowerCase();
        switch (marketType) {
            case "RESULT":
                if (cellHtml.contains("match_result.1")) return "П1";
                if (cellHtml.contains("match_result.draw")) return "X";
                if (cellHtml.contains("match_result.3")) return "П2";
                break;
            case "DOUBLE_CHANCE":
                if (cellHtml.contains("result.hd")) return "1X";
                if (cellHtml.contains("result.ha")) return "12";
                if (cellHtml.contains("result.ad")) return "X2";
                break;
            case "HANDICAP":
                if (cellHtml.contains("hb_h") && lineValue != null) return "Фора " + lineValue + " (хозяева)";
                if (cellHtml.contains("hb_a") && lineValue != null) {
                    try {
                        BigDecimal val = new BigDecimal(lineValue);
                        BigDecimal awayVal = val.negate();
                        return "Фора " + awayVal + " (гости)";
                    } catch (Exception e) {
                        return "Фора (гости)";
                    }
                }
                break;
            case "TOTAL":
                if (cellHtml.contains("under") && lineValue != null) return "Меньше " + lineValue;
                if (cellHtml.contains("over") && lineValue != null) return "Больше " + lineValue;
                break;
        }
        return cell.textContent().trim();
    }

    private String extractLineValue(String cellText) {
        Pattern pattern = Pattern.compile("\\(([-+]?\\d+\\.?\\d*)\\)");
        Matcher matcher = pattern.matcher(cellText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private BigDecimal parseOdds(String priceText) {
        try {
            String cleaned = priceText.replace(",", ".");
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return marketsCache.getOrDefault(externalEventId, new ArrayList<>());
    }
}