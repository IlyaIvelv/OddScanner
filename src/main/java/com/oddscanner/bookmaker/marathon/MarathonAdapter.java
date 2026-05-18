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

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(1)
public class MarathonAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);
    private static final String BASE_URL = "https://www.marathonbet.ru";
    private static final String SITEMAP_URL = "https://www.marathonbet.ru/sitemap/sitemap-prematch-su.xml";

    // ============ НАСТРОЙКИ ============
    private static final int THREAD_POOL_SIZE = 4;
    private static final int MIN_DELAY_MS = 2000;
    private static final int MAX_DELAY_MS = 4000;
    private static final int BURST_LIMIT = 20;
    private static final int BURST_PAUSE_MS = 3000;
    private static final int MAX_EVENTS_PER_RUN = 200;
    private static final int PAGE_TIMEOUT_MS = 20000;
    private static final int CONNECTION_TIMEOUT_MS = 15000;

    private static final List<String> SPORT_FILTERS = Arrays.asList(
            "/Football/",
            "/Tennis/",
            "/Basketball/",
            "/Ice+Hockey/"
    );

    private static final List<String> USER_AGENTS = Arrays.asList(
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:109.0) Gecko/20100101 Firefox/119.0"
    );

    private final ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);
    private final Map<String, List<RawMarket>> marketsCache = new ConcurrentHashMap<>();
    private final Map<String, String> teamCache = new ConcurrentHashMap<>();

    @Override
    public String code() {
        return "marathon";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        long startTime = System.currentTimeMillis();
        log.info("=== MARATHON ПАРСИНГ START ===");

        // Шаг 1: Получаем реальные URL событий
        List<String> eventUrls = fetchRealEventUrls();
        if (eventUrls.isEmpty()) {
            log.error("Не удалось получить ссылки на события");
            return new ArrayList<>();
        }

        if (eventUrls.size() > MAX_EVENTS_PER_RUN) {
            eventUrls = eventUrls.subList(0, MAX_EVENTS_PER_RUN);
            log.info("📊 Ограничиваем до {} событий", MAX_EVENTS_PER_RUN);
        }

        log.info("📊 Начинаем парсинг {} событий в {} потоков", eventUrls.size(), THREAD_POOL_SIZE);

        // Шаг 2: Парсим события параллельно
        List<RawEvent> allEvents = parseEventsInParallel(eventUrls);

        // Шаг 3: Удаляем дубликаты
        Map<String, RawEvent> uniqueEvents = new LinkedHashMap<>();
        for (RawEvent event : allEvents) {
            if (event != null && event.getExternalId() != null) {
                uniqueEvents.putIfAbsent(event.getExternalId(), event);
            }
        }

        long duration = System.currentTimeMillis() - startTime;
        log.info("=== MARATHON ИТОГО: {} уникальных событий за {} сек ===",
                uniqueEvents.size(), duration / 1000);

        return new ArrayList<>(uniqueEvents.values());
    }

    /**
     * Получает реальные URL событий (не категории!)
     */
    private List<String> fetchRealEventUrls() {
        List<String> allEventUrls = Collections.synchronizedList(new ArrayList<>());

        // Получаем категории из sitemap
        List<String> categoryUrls = fetchCategoryUrlsFromSitemap();
        log.info("📁 Найдено {} категорий в sitemap", categoryUrls.size());

        if (categoryUrls.isEmpty()) {
            return allEventUrls;
        }

        // Ограничиваем количество категорий для первого запуска
        int categoriesToProcess = Math.min(categoryUrls.size(), 30);
        List<String> limitedCategories = categoryUrls.subList(0, categoriesToProcess);
        log.info("📁 Обрабатываем {} категорий", limitedCategories.size());

        AtomicInteger processedCategories = new AtomicInteger(0);

        // Параллельно парсим категории
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String categoryUrl : limitedCategories) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    List<String> events = extractEventUrlsFromCategoryPage(categoryUrl);
                    allEventUrls.addAll(events);

                    int processed = processedCategories.incrementAndGet();
                    if (processed % 5 == 0) {
                        log.info("📁 Обработано категорий: {}/{}", processed, limitedCategories.size());
                    }

                    // Задержка между запросами категорий
                    Thread.sleep(MIN_DELAY_MS + new Random().nextInt(MAX_DELAY_MS - MIN_DELAY_MS));
                } catch (Exception e) {
                    log.debug("Ошибка обработки категории {}: {}", categoryUrl, e.getMessage());
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(3, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Парсинг категорий прерван: {}", e.getMessage());
        }

        log.info("✅ Найдено {} реальных событий", allEventUrls.size());
        return allEventUrls;
    }

    /**
     * Получает URL категорий из sitemap
     */
    private List<String> fetchCategoryUrlsFromSitemap() {
        List<String> urls = new ArrayList<>();

        try {
            URL url = new URL(SITEMAP_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", USER_AGENTS.get(0));
            conn.setConnectTimeout(CONNECTION_TIMEOUT_MS);
            conn.setReadTimeout(CONNECTION_TIMEOUT_MS);

            if (conn.getResponseCode() != 200) {
                log.error("Sitemap вернул код: {}", conn.getResponseCode());
                return urls;
            }

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    Pattern pattern = Pattern.compile("<loc>([^<]+)</loc>");
                    Matcher matcher = pattern.matcher(line);
                    while (matcher.find()) {
                        String locUrl = matcher.group(1);
                        if (locUrl.contains("/su/betting/") && !locUrl.matches(".*/\\d+$")) {
                            for (String filter : SPORT_FILTERS) {
                                if (locUrl.contains(filter)) {
                                    urls.add(locUrl);
                                    break;
                                }
                            }
                        }
                    }
                }
            }
            conn.disconnect();

            log.info("✅ Найдено {} категорий", urls.size());

        } catch (Exception e) {
            log.error("Ошибка загрузки sitemap: {}", e.getMessage());
        }

        return urls;
    }

    /**
     * Извлекает URL реальных событий со страницы категории
     */
    private List<String> extractEventUrlsFromCategoryPage(String categoryUrl) {
        List<String> eventUrls = new ArrayList<>();
        Playwright playwright = null;
        Browser browser = null;
        Page page = null;

        try {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setSlowMo(50)
                    .setArgs(Arrays.asList("--no-sandbox", "--disable-dev-shm-usage")));

            page = browser.newPage();
            page.setDefaultTimeout(PAGE_TIMEOUT_MS);

            // Переходим на страницу категории
            page.navigate(categoryUrl, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Ждём загрузки ссылок на события
            Thread.sleep(1000);

            // Ищем все ссылки на странице
            List<ElementHandle> links = page.querySelectorAll("a");

            for (ElementHandle link : links) {
                try {
                    String href = link.getAttribute("href");
                    if (href != null && href.startsWith("/su/betting/")) {
                        // Проверяем, что это ссылка на событие (заканчивается на цифры)
                        Pattern eventPattern = Pattern.compile("/su/betting/.+/(\\d+)$");
                        Matcher matcher = eventPattern.matcher(href);
                        if (matcher.find()) {
                            String fullUrl = BASE_URL + href;
                            if (!eventUrls.contains(fullUrl)) {
                                eventUrls.add(fullUrl);
                            }
                        }
                    }
                } catch (Exception e) {
                    // Пропускаем проблемные ссылки
                }
            }

            log.debug("Из категории {} получено {} событий", categoryUrl, eventUrls.size());

        } catch (Exception e) {
            log.debug("Ошибка парсинга категории {}: {}", categoryUrl, e.getMessage());
        } finally {
            try { if (page != null) page.close(); } catch (Exception e) {}
            try { if (browser != null) browser.close(); } catch (Exception e) {}
            try { if (playwright != null) playwright.close(); } catch (Exception e) {}
        }

        return eventUrls;
    }

    /**
     * Параллельный парсинг событий
     */
    private List<RawEvent> parseEventsInParallel(List<String> eventUrls) {
        List<RawEvent> allEvents = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger requestCount = new AtomicInteger(0);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        ThreadLocal<Playwright> playwrightLocal = ThreadLocal.withInitial(() -> {
            Playwright pw = Playwright.create();
            log.debug("Создан Playwright для потока {}", Thread.currentThread().getName());
            return pw;
        });

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String eventUrl : eventUrls) {
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    RawEvent event = parseSingleEvent(eventUrl, playwrightLocal.get());
                    if (event != null && !event.getHomeTeamName().isEmpty()) {
                        allEvents.add(event);
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }

                    int total = requestCount.incrementAndGet();

                    if (total % BURST_LIMIT == 0 && total < eventUrls.size()) {
                        log.info("⏸️ Прогресс: {}/{} (успешно: {}, ошибок: {})",
                                total, eventUrls.size(), successCount.get(), failCount.get());
                        Thread.sleep(BURST_PAUSE_MS);
                    } else {
                        Thread.sleep(MIN_DELAY_MS + new Random().nextInt(MAX_DELAY_MS - MIN_DELAY_MS));
                    }

                } catch (Exception e) {
                    failCount.incrementAndGet();
                    log.debug("Ошибка парсинга: {}", e.getMessage());
                }
            }, executor));
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            log.error("Парсинг прерван: {}", e.getMessage());
        }

        log.info("📊 Результат парсинга: успешно {} из {}", successCount.get(), eventUrls.size());
        return allEvents;
    }

    /**
     * Парсинг одного события
     */
    private RawEvent parseSingleEvent(String eventUrl, Playwright playwright) {
        Browser browser = null;
        Page page = null;

        try {
            String userAgent = USER_AGENTS.get(new Random().nextInt(USER_AGENTS.size()));

            BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setSlowMo(100)
                    .setArgs(Arrays.asList(
                            "--no-sandbox",
                            "--disable-dev-shm-usage",
                            "--disable-blink-features=AutomationControlled"
                    ));

            browser = playwright.chromium().launch(launchOptions);

            Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
                    .setUserAgent(userAgent)
                    .setViewportSize(1280, 720)
                    .setLocale("ru-RU")
                    .setTimezoneId("Europe/Moscow");

            var context = browser.newContext(contextOptions);
            page = context.newPage();

            page.navigate(eventUrl, new Page.NavigateOptions().setTimeout(PAGE_TIMEOUT_MS));
            page.waitForLoadState(LoadState.NETWORKIDLE);

            // Парсим команды
            String[] teams = parseTeamsFromPage(page, eventUrl);
            if (teams[0].isEmpty() || teams[1].isEmpty()) {
                return null;
            }

            String eventId = extractEventIdFromUrl(eventUrl);
            List<RawMarket> markets = parseMarketsFromPage(page, eventId);

            RawEvent rawEvent = new RawEvent();
            rawEvent.setExternalId(eventId);
            rawEvent.setHomeTeamName(teams[0]);
            rawEvent.setAwayTeamName(teams[1]);
            rawEvent.setStartTime(LocalDateTime.now().plusDays(7));
            rawEvent.setLeagueName(extractLeagueNameFromUrl(eventUrl));
            rawEvent.setSportName(detectSport(eventUrl));
            rawEvent.setEventUrl(eventUrl);
            rawEvent.setMarkets(markets);

            return rawEvent;

        } catch (Exception e) {
            log.debug("Ошибка парсинга {}: {}", eventUrl, e.getMessage());
            return null;
        } finally {
            try { if (page != null) page.close(); } catch (Exception e) {}
            try { if (browser != null) browser.close(); } catch (Exception e) {}
        }
    }

    /**
     * Парсинг команд со страницы
     */
    private String[] parseTeamsFromPage(Page page, String eventUrl) {
        if (teamCache.containsKey(eventUrl)) {
            String cached = teamCache.get(eventUrl);
            String[] parts = cached.split("\\|");
            if (parts.length == 2) return parts;
        }

        String homeTeam = "";
        String awayTeam = "";

        try {
            // Способ 1: H1 заголовок
            ElementHandle h1 = page.querySelector("h1");
            if (h1 != null) {
                String h1Text = h1.textContent().trim();
                if (h1Text.contains(" - ") || h1Text.contains("–")) {
                    String[] parts = h1Text.split("\\s+[-–]\\s+");
                    if (parts.length == 2) {
                        homeTeam = cleanTeamName(parts[0]);
                        awayTeam = cleanTeamName(parts[1]);
                    }
                }
            }

            // Способ 2: Специальные классы
            if (homeTeam.isEmpty()) {
                var members = page.querySelectorAll(".memberName, .member-name");
                if (members.size() >= 2) {
                    homeTeam = cleanTeamName(members.get(0).textContent());
                    awayTeam = cleanTeamName(members.get(1).textContent());
                }
            }

            if (!homeTeam.isEmpty() && !awayTeam.isEmpty()) {
                teamCache.put(eventUrl, homeTeam + "|" + awayTeam);
            }

        } catch (Exception e) {
            log.debug("Ошибка парсинга команд: {}", e.getMessage());
        }

        return new String[]{homeTeam, awayTeam};
    }

    /**
     * Парсинг коэффициентов
     */
    private List<RawMarket> parseMarketsFromPage(Page page, String eventId) {
        List<RawMarket> markets = new ArrayList<>();

        try {
            var priceButtons = page.querySelectorAll("[data-market-type]");

            for (ElementHandle btn : priceButtons) {
                try {
                    String marketType = btn.getAttribute("data-market-type");
                    if (marketType == null) continue;

                    ElementHandle span = btn.querySelector("span");
                    if (span == null) continue;

                    String priceText = span.textContent().trim();
                    BigDecimal odds = parseOdds(priceText);
                    if (odds == null) continue;

                    String mappedMarketType = mapMarketType(marketType);
                    String outcomeKey = getOutcomeKey(btn, marketType);

                    RawMarket market = findOrCreateMarket(markets, eventId, mappedMarketType);
                    RawOutcome outcome = new RawOutcome();
                    outcome.setExternalMarketId(market.getExternalId());
                    outcome.setOutcomeKeyName(outcomeKey);
                    outcome.setOutcomeValueDescription(outcomeKey);
                    outcome.setOdds(odds);
                    outcome.setActive(true);

                    if (market.getOutcomes() == null) {
                        market.setOutcomes(new ArrayList<>());
                    }
                    market.getOutcomes().add(outcome);

                } catch (Exception e) {
                    // Пропускаем
                }
            }

        } catch (Exception e) {
            log.debug("Ошибка парсинга рынков: {}", e.getMessage());
        }

        return markets;
    }

    private RawMarket findOrCreateMarket(List<RawMarket> markets, String eventId, String marketType) {
        String marketId = eventId + "_" + marketType;
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
        newMarket.setOutcomes(new ArrayList<>());
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

    private String getOutcomeKey(ElementHandle btn, String marketType) {
        String html = btn.innerHTML().toLowerCase();
        switch (marketType) {
            case "RESULT":
                if (html.contains("match_result.1")) return "HOME_WIN";
                if (html.contains("match_result.draw")) return "DRAW";
                if (html.contains("match_result.3")) return "AWAY_WIN";
                break;
            case "DOUBLE_CHANCE":
                if (html.contains("result.hd")) return "HOME_OR_DRAW";
                if (html.contains("result.ha")) return "HOME_OR_AWAY";
                if (html.contains("result.ad")) return "DRAW_OR_AWAY";
                break;
            case "HANDICAP":
                if (html.contains("hb_h")) return "HOME_HANDICAP";
                if (html.contains("hb_a")) return "AWAY_HANDICAP";
                break;
            case "TOTAL":
                if (html.contains("under")) return "UNDER";
                if (html.contains("over")) return "OVER";
                break;
        }
        return "UNKNOWN";
    }

    private BigDecimal parseOdds(String priceText) {
        try {
            String cleaned = priceText.replace(",", ".");
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    private String cleanTeamName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "");
    }

    private String extractEventIdFromUrl(String url) {
        Pattern pattern = Pattern.compile("/(\\d+)$");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return String.valueOf(Math.abs(url.hashCode()));
    }

    private String extractLeagueNameFromUrl(String url) {
        Pattern pattern = Pattern.compile("/su/betting/[^/]+/([^/]+(?:/[^/]+)?)");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1).replace("+-+", " - ").replace("+", " ");
        }
        return "Unknown League";
    }

    private String detectSport(String url) {
        if (url.contains("/Football/")) return "Football";
        if (url.contains("/Tennis/")) return "Tennis";
        if (url.contains("/Basketball/")) return "Basketball";
        if (url.contains("/Ice+Hockey/")) return "Ice Hockey";
        return "Unknown";
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return marketsCache.getOrDefault(externalEventId, new ArrayList<>());
    }
}