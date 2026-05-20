package com.oddscanner.bookmaker.fonbet;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Order(2)
public class FonbetAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(FonbetAdapter.class);

    // ==================== МАППИНГ SPORT ID → НАЗВАНИЕ ВИДА СПОРТА/ЛИГИ ====================
    private static final Map<Integer, String> SPORT_MAPPING = new HashMap<>();

    static {
        // Основные виды спорта
        SPORT_MAPPING.put(1, "Футбол");
        SPORT_MAPPING.put(2, "Хоккей");
        SPORT_MAPPING.put(3, "Баскетбол");
        SPORT_MAPPING.put(4, "Теннис");
        SPORT_MAPPING.put(5, "Волейбол");
        SPORT_MAPPING.put(6, "MMA/Бокс");
        SPORT_MAPPING.put(7, "Киберспорт");

        // Настольный теннис, дартс, снукер и т.д.
        SPORT_MAPPING.put(142073, "Настольный теннис/Дартс");
        SPORT_MAPPING.put(142063, "Настольный теннис/Дартс");
        SPORT_MAPPING.put(142072, "Настольный теннис/Дартс");
        SPORT_MAPPING.put(142076, "Настольный теннис/Дартс");
        SPORT_MAPPING.put(142077, "Настольный теннис/Дартс");

        // Виртуальный спорт
        SPORT_MAPPING.put(110368, "Виртуальный спорт");
        SPORT_MAPPING.put(129372, "Виртуальный спорт");
        SPORT_MAPPING.put(129373, "Виртуальный спорт");
        SPORT_MAPPING.put(118263, "Виртуальный спорт");
        SPORT_MAPPING.put(78347, "Виртуальный спорт");

        // Киберспорт
        SPORT_MAPPING.put(141708, "Киберспорт");
        SPORT_MAPPING.put(141707, "Киберспорт");
        SPORT_MAPPING.put(142113, "Киберспорт");
        SPORT_MAPPING.put(88892, "Киберспорт");
        SPORT_MAPPING.put(141534, "Киберспорт");
        SPORT_MAPPING.put(99302, "Киберспорт");
        SPORT_MAPPING.put(134976, "Киберспорт");
        SPORT_MAPPING.put(132620, "Киберспорт");
        SPORT_MAPPING.put(124308, "Киберспорт");
        SPORT_MAPPING.put(139013, "Киберспорт");
        SPORT_MAPPING.put(141936, "Киберспорт");
        SPORT_MAPPING.put(123682, "Киберспорт");
        SPORT_MAPPING.put(131964, "Киберспорт");
        SPORT_MAPPING.put(108404, "Киберспорт");
        SPORT_MAPPING.put(99432, "Киберспорт");
        SPORT_MAPPING.put(139699, "Киберспорт");
        SPORT_MAPPING.put(141971, "Киберспорт");
        SPORT_MAPPING.put(140454, "Киберспорт");
        SPORT_MAPPING.put(78691, "Киберспорт");
        SPORT_MAPPING.put(140368, "Киберспорт");
        SPORT_MAPPING.put(142014, "Киберспорт");
        SPORT_MAPPING.put(142016, "Киберспорт");
        SPORT_MAPPING.put(81277, "Киберспорт");
        SPORT_MAPPING.put(108900, "Киберспорт");
        SPORT_MAPPING.put(115130, "Киберспорт");
        SPORT_MAPPING.put(118783, "Киберспорт");
        SPORT_MAPPING.put(119473, "Киберспорт");
        SPORT_MAPPING.put(119864, "Киберспорт");
        SPORT_MAPPING.put(140837, "Киберспорт");
        SPORT_MAPPING.put(141588, "Киберспорт");
        SPORT_MAPPING.put(141721, "Киберспорт");
        SPORT_MAPPING.put(81282, "Киберспорт");
        SPORT_MAPPING.put(81297, "Киберспорт");
        SPORT_MAPPING.put(140830, "Киберспорт");
        SPORT_MAPPING.put(141355, "Киберспорт");
        SPORT_MAPPING.put(141367, "Киберспорт");
        SPORT_MAPPING.put(141589, "Киберспорт");
        SPORT_MAPPING.put(136923, "Киберспорт");
        SPORT_MAPPING.put(124826, "Киберспорт");
        SPORT_MAPPING.put(137706, "Киберспорт");
        SPORT_MAPPING.put(125126, "Киберспорт");
        SPORT_MAPPING.put(125180, "Киберспорт");
        SPORT_MAPPING.put(125664, "Киберспорт");
        SPORT_MAPPING.put(137704, "Киберспорт");
        SPORT_MAPPING.put(84139, "Киберспорт");
        SPORT_MAPPING.put(99029, "Киберспорт");
        SPORT_MAPPING.put(104719, "Киберспорт");
        SPORT_MAPPING.put(105712, "Киберспорт");
        SPORT_MAPPING.put(115726, "Киберспорт");
        SPORT_MAPPING.put(141206, "Киберспорт");
        SPORT_MAPPING.put(141748, "Киберспорт");
        SPORT_MAPPING.put(107146, "Киберспорт");
        SPORT_MAPPING.put(82460, "Киберспорт");
        SPORT_MAPPING.put(84841, "Киберспорт");
        SPORT_MAPPING.put(55117, "Киберспорт");
        SPORT_MAPPING.put(55118, "Киберспорт");
        SPORT_MAPPING.put(55491, "Киберспорт");
        SPORT_MAPPING.put(76186, "Киберспорт");
        SPORT_MAPPING.put(93121, "Киберспорт");
        SPORT_MAPPING.put(116212, "Киберспорт");
        SPORT_MAPPING.put(73591, "Киберспорт");
        SPORT_MAPPING.put(60199, "Киберспорт");
        SPORT_MAPPING.put(71596, "Киберспорт");
        SPORT_MAPPING.put(138496, "Киберспорт");
        SPORT_MAPPING.put(109979, "Киберспорт");
        SPORT_MAPPING.put(76417, "Киберспорт");
        SPORT_MAPPING.put(75987, "Киберспорт");
        SPORT_MAPPING.put(131027, "Киберспорт");
        SPORT_MAPPING.put(131029, "Киберспорт");
        SPORT_MAPPING.put(131031, "Киберспорт");
        SPORT_MAPPING.put(131033, "Киберспорт");
        SPORT_MAPPING.put(131037, "Киберспорт");
        SPORT_MAPPING.put(131039, "Киберспорт");
        SPORT_MAPPING.put(131041, "Киберспорт");
        SPORT_MAPPING.put(131043, "Киберспорт");
        SPORT_MAPPING.put(131045, "Киберспорт");
        SPORT_MAPPING.put(131047, "Киберспорт");
        SPORT_MAPPING.put(131049, "Киберспорт");
        SPORT_MAPPING.put(131051, "Киберспорт");
        SPORT_MAPPING.put(131055, "Киберспорт");
        SPORT_MAPPING.put(131697, "Киберспорт");

        // Гандбол
        SPORT_MAPPING.put(141803, "Гандбол");
        SPORT_MAPPING.put(141804, "Гандбол");

        // Регби
        SPORT_MAPPING.put(64142, "Регби");
        SPORT_MAPPING.put(58007, "Регби");
        SPORT_MAPPING.put(70511, "Регби");
        SPORT_MAPPING.put(62230, "Регби");

        // Бейсбол
        SPORT_MAPPING.put(11676, "Бейсбол");

        // Американский футбол
        SPORT_MAPPING.put(20281, "Американский футбол");
    }

    // ==================== СТАТИЧЕСКИЙ МАППИНГ ID → НАЗВАНИЕ ЛИГИ (ДЛЯ ИЗВЕСТНЫХ) ====================
    private static final Map<Integer, String> LEAGUE_MAPPING = new HashMap<>();

    static {
        // === АНГЛИЯ ===
        LEAGUE_MAPPING.put(15971, "Англия. Премьер-лига");
        LEAGUE_MAPPING.put(11918, "Англия. Чемпионшип");
        LEAGUE_MAPPING.put(11950, "Англия. Лига 1");
        LEAGUE_MAPPING.put(11951, "Англия. Лига 2");
        LEAGUE_MAPPING.put(141681, "Англия. Кубок");

        // === ИСПАНИЯ ===
        LEAGUE_MAPPING.put(11922, "Испания. Примера");
        LEAGUE_MAPPING.put(11953, "Испания. Сегунда");

        // === ИТАЛИЯ ===
        LEAGUE_MAPPING.put(11924, "Италия. Серия А");
        LEAGUE_MAPPING.put(35040, "Италия. Серия Б");

        // === ГЕРМАНИЯ ===
        LEAGUE_MAPPING.put(11916, "Германия. Бундеслига");
        LEAGUE_MAPPING.put(11987, "Германия. Вторая Бундеслига");

        // === ФРАНЦИЯ ===
        LEAGUE_MAPPING.put(11920, "Франция. Лига 1");

        // === РОССИЯ ===
        LEAGUE_MAPPING.put(11935, "Россия. Премьер-лига");
        LEAGUE_MAPPING.put(80073, "Россия. Первая лига");
        LEAGUE_MAPPING.put(90707, "Россия. Кубок");

        // === ПОРТУГАЛИЯ ===
        LEAGUE_MAPPING.put(11939, "Португалия. Примейра-лига");

        // === НОРВЕГИЯ ===
        LEAGUE_MAPPING.put(11988, "Норвегия. 1-й дивизион");
        LEAGUE_MAPPING.put(52316, "Норвегия. 2-й дивизион - группа 1");
        LEAGUE_MAPPING.put(19848, "Норвегия. 2-й дивизион - группа 2");
        LEAGUE_MAPPING.put(12212, "Норвегия. 3-й дивизион - группа 1");
        LEAGUE_MAPPING.put(15336, "Норвегия. 3-й дивизион - группа 2");
        LEAGUE_MAPPING.put(25331, "Норвегия. 3-й дивизион - группа 3");
        LEAGUE_MAPPING.put(57665, "Норвегия. 3-й дивизион - группа 4");

        // === ПОЛЬША ===
        LEAGUE_MAPPING.put(12930, "Польша. Первая лига");
        LEAGUE_MAPPING.put(12955, "Польша. Вторая лига");
        LEAGUE_MAPPING.put(62481, "Польша. Третья лига");
    }

    private static final String BASE_URL = "https://fon.bet";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, List<RawMarket>> marketsCache = new HashMap<>();
    private final Map<String, String> teamCache = new ConcurrentHashMap<>();

    private final Map<Integer, String> dynamicLeagueCache = new ConcurrentHashMap<>();
    private boolean leagueCacheLoaded = false;

    private String cachedApiBaseUrl = null;
    private long lastApiBaseUrlFetch = 0;
    private String cachedApiHost = null;

    private boolean debugLogged = false;

    @Override
    public String code() {
        return "fonbet";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА FONBET ===");

        List<RawEvent> allEvents = new ArrayList<>();
        marketsCache.clear();

        try {
            String apiBaseUrl = discoverApiBaseUrl();
            if (apiBaseUrl == null) {
                log.error("Не удалось определить API базовый URL");
                return allEvents;
            }

            String apiUrl = apiBaseUrl + "/ma/events/list?lang=ru&scopeMarket=1600&version=" + System.currentTimeMillis();

            if (!debugLogged) {
                log.info("API URL получен");
                debugLogged = true;
            }

            String response = fetchApiData(apiUrl);
            if (response == null) {
                log.error("Не удалось получить данные от API");
                return allEvents;
            }

            allEvents = parseApiResponse(response);

        } catch (Exception e) {
            log.error("Ошибка при парсинге Fonbet: {}", e.getMessage(), e);
        }

        log.info("=== FONBET: Всего собрано {} событий ===", allEvents.size());
        return allEvents;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return marketsCache.getOrDefault(externalEventId, new ArrayList<>());
    }

    private String resolveLeagueName(JsonNode eventNode) {
        Integer sportId = getIntField(eventNode, "sportId");
        String eventName = getStringField(eventNode, "name", "title", "eventName");

        if (sportId != null && dynamicLeagueCache.containsKey(sportId)) {
            return dynamicLeagueCache.get(sportId);
        }

        if (sportId != null && LEAGUE_MAPPING.containsKey(sportId)) {
            return LEAGUE_MAPPING.get(sportId);
        }

        if (eventName != null && !eventName.isEmpty() && !"null".equalsIgnoreCase(eventName)) {
            String[] separators = {" - ", " – ", " : ", " vs "};
            for (String sep : separators) {
                int idx = eventName.indexOf(sep);
                if (idx > 0) {
                    String leagueFromName = eventName.substring(0, idx).trim();
                    if (leagueFromName.contains(".") ||
                            leagueFromName.endsWith("Лига") ||
                            leagueFromName.endsWith("League") ||
                            leagueFromName.contains("Кубок") ||
                            leagueFromName.contains("Чемпионат")) {
                        return leagueFromName;
                    }
                }
            }
        }

        if (sportId != null && SPORT_MAPPING.containsKey(sportId)) {
            return SPORT_MAPPING.get(sportId);
        }

        String homeTeam = getStringField(eventNode, "team1", "homeTeam", "home");
        String awayTeam = getStringField(eventNode, "team2", "awayTeam", "away");

        return "Неизвестная лига";
    }

    private String getSportName(JsonNode eventNode) {
        Integer sportId = getIntField(eventNode, "sportId");
        if (sportId != null) {
            switch (sportId) {
                case 1: return "Футбол";
                case 2: return "Хоккей";
                case 3: return "Баскетбол";
                case 4: return "Теннис";
                case 5: return "Волейбол";
                case 6: return "MMA/Бокс";
                case 7: return "Киберспорт";
                default: return "Спорт " + sportId;
            }
        }
        return "Unknown Sport";
    }

    private Integer getIntField(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isNumber()) {
                return field.asInt();
            }
        }
        return null;
    }

    private String getStringField(JsonNode node, String... fieldNames) {
        if (node == null) return null;
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isTextual()) {
                String value = field.asText();
                if (value != null && !value.isEmpty() && !"null".equalsIgnoreCase(value)) {
                    return value;
                }
            }
        }
        return null;
    }

    private String discoverApiBaseUrl() {
        if (cachedApiBaseUrl != null && System.currentTimeMillis() - lastApiBaseUrlFetch < 300000) {
            return cachedApiBaseUrl;
        }

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Page page = browser.newPage();
            page.setExtraHTTPHeaders(Map.of(
                    "Accept-Language", "ru-RU,ru;q=0.9",
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ));

            page.navigate(BASE_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            List<String> apiUrls = new ArrayList<>();
            page.onRequest(request -> {
                String url = request.url();
                if (url.contains("/ma/events/list") && url.contains("bk6bba-resources")) {
                    String base = url.replaceAll("/ma/events/list\\?.*$", "");
                    if (!apiUrls.contains(base)) {
                        apiUrls.add(base);
                    }
                }
            });

            page.reload();
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            if (!apiUrls.isEmpty()) {
                cachedApiBaseUrl = apiUrls.get(0);
                cachedApiHost = cachedApiBaseUrl.replaceAll("/ma/events/list.*$", "");
                lastApiBaseUrlFetch = System.currentTimeMillis();
                page.close();
                browser.close();
                return cachedApiBaseUrl;
            }

            page.close();
            browser.close();

        } catch (Exception e) {
            log.error("Ошибка при обнаружении API URL: {}", e.getMessage());
        }

        return null;
    }

    private String getApiHost() {
        if (cachedApiHost != null) {
            return cachedApiHost;
        }
        discoverApiBaseUrl();
        return cachedApiHost != null ? cachedApiHost : "https://line-lb61-w.bk6bba-resources.com";
    }

    private String fetchApiData(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "*/*")
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + "/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            byte[] body = response.body();
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");

            if ("gzip".equals(contentEncoding) || "deflate".equals(contentEncoding)) {
                return decompressGzip(body);
            }
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        return null;
    }

    private List<RawEvent> parseApiResponse(String jsonResponse) {
        List<RawEvent> events = new ArrayList<>();

        try {
            JsonNode root = mapper.readTree(jsonResponse);

            if (!leagueCacheLoaded) {
                Map<Integer, String> freshMapping = buildLeagueMapping(root);
                dynamicLeagueCache.putAll(freshMapping);
                leagueCacheLoaded = true;
                log.info("Загружено {} лиг из справочника", dynamicLeagueCache.size());
            }

            JsonNode eventsNode = findEventsNode(root);

            if (eventsNode == null || !eventsNode.isArray()) {
                log.error("Не найден массив событий в ответе API");
                return events;
            }

            if (!debugLogged) {
                log.info("Найдено {} событий в ответе API", eventsNode.size());
            }

            for (JsonNode eventNode : eventsNode) {
                try {
                    if (eventNode.has("level") && eventNode.get("level").asInt() != 1) {
                        continue;
                    }

                    RawEvent event = parseEventNode(eventNode);
                    if (event != null && event.getHomeTeamName() != null && event.getAwayTeamName() != null) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    // Подавляем лог для единичных ошибок
                }
            }

        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", e.getMessage(), e);
        }

        return events;
    }

    private JsonNode findEventsNode(JsonNode root) {
        String[] possiblePaths = {"events", "data", "items", "list", "result"};

        for (String path : possiblePaths) {
            JsonNode node = root.get(path);
            if (node != null && node.isArray() && node.size() > 0) {
                return node;
            }
        }

        if (root.isArray() && root.size() > 0) {
            return root;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isArray() && value.size() > 0) {
                JsonNode first = value.get(0);
                if (first.has("homeTeam") || first.has("team1") || first.has("participants")) {
                    return value;
                }
            }
        }

        return null;
    }

    private RawEvent parseEventNode(JsonNode eventNode) {
        String eventId = String.valueOf(getIntField(eventNode, "id"));
        if (eventId == null || "null".equals(eventId)) {
            eventId = "fonbet_" + UUID.randomUUID().toString();
        }

        String homeTeam = parseHomeTeam(eventNode);
        String awayTeam = parseAwayTeam(eventNode);

        if (homeTeam == null || awayTeam == null) {
            return null;
        }

        homeTeam = cleanTeamName(homeTeam);
        awayTeam = cleanTeamName(awayTeam);

        LocalDateTime startTime = parseStartTime(eventNode);
        String league = resolveLeagueName(eventNode);
        List<RawMarket> markets = parseMarkets(eventNode, eventId);
        marketsCache.put(eventId, markets);

        RawEvent event = new RawEvent();
        event.setExternalId(eventId);
        event.setHomeTeamName(homeTeam);
        event.setAwayTeamName(awayTeam);
        event.setStartTime(startTime != null ? startTime : LocalDateTime.now().plusDays(7));
        event.setLeagueName(league);
        event.setSportName(getSportName(eventNode));
        event.setEventUrl(BASE_URL + "/sport/football/" + eventId);
        event.setMarkets(markets);

        return event;
    }

    private String parseHomeTeam(JsonNode eventNode) {
        String team = getStringField(eventNode, "homeTeam", "team1", "home", "participant1", "homeName");
        if (team != null) return team;

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return getStringField(participants.get(0), "name", "title");
        }

        return null;
    }

    private String parseAwayTeam(JsonNode eventNode) {
        String team = getStringField(eventNode, "awayTeam", "team2", "away", "participant2", "awayName");
        if (team != null) return team;

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return getStringField(participants.get(1), "name", "title");
        }

        return null;
    }

    private LocalDateTime parseStartTime(JsonNode eventNode) {
        String timeStr = getStringField(eventNode, "startTime", "date", "kickoff", "startDate");
        if (timeStr != null) {
            try {
                return LocalDateTime.parse(timeStr);
            } catch (Exception e) {
                // Подавляем
            }
        }
        return null;
    }

    private List<RawMarket> parseMarkets(JsonNode eventNode, String eventId) {
        List<RawMarket> markets = new ArrayList<>();

        JsonNode marketsNode = eventNode.get("markets");
        if (marketsNode == null) {
            marketsNode = eventNode.get("betGroups");
        }

        if (marketsNode != null && marketsNode.isArray()) {
            for (JsonNode marketNode : marketsNode) {
                RawMarket market = parseMarketNode(marketNode, eventId);
                if (market != null && market.getOutcomes() != null && !market.getOutcomes().isEmpty()) {
                    markets.add(market);
                }
            }
        }

        return markets;
    }

    private RawMarket parseMarketNode(JsonNode marketNode, String eventId) {
        String marketId = getStringField(marketNode, "id");
        if (marketId == null) {
            marketId = eventId + "_" + UUID.randomUUID().toString();
        }

        String marketType = getStringField(marketNode, "type", "marketType", "name");
        if (marketType == null) {
            marketType = "WIN_DRAW_WIN";
        }

        String period = getStringField(marketNode, "period", "periodName");
        if (period == null) {
            period = "FULL_TIME";
        }

        BigDecimal line = null;
        JsonNode lineNode = marketNode.get("line");
        if (lineNode != null && lineNode.isNumber()) {
            line = BigDecimal.valueOf(lineNode.asDouble());
        }

        List<RawOutcome> outcomes = parseOutcomes(marketNode, marketId);

        if (outcomes.isEmpty()) {
            return null;
        }

        return RawMarket.builder()
                .externalId(marketId)
                .externalEventId(eventId)
                .marketTypeName(normalizeMarketType(marketType))
                .periodName(period)
                .line(line)
                .outcomes(outcomes)
                .build();
    }

    private List<RawOutcome> parseOutcomes(JsonNode marketNode, String marketId) {
        List<RawOutcome> outcomes = new ArrayList<>();

        JsonNode outcomesNode = marketNode.get("outcomes");
        if (outcomesNode == null) {
            outcomesNode = marketNode.get("selections");
        }

        if (outcomesNode != null && outcomesNode.isArray()) {
            for (JsonNode outcomeNode : outcomesNode) {
                RawOutcome outcome = parseOutcomeNode(outcomeNode, marketId);
                if (outcome != null && outcome.getOdds() != null) {
                    outcomes.add(outcome);
                }
            }
        }

        return outcomes;
    }

    private RawOutcome parseOutcomeNode(JsonNode outcomeNode, String marketId) {
        String outcomeKey = getStringField(outcomeNode, "key", "type", "outcomeKey");
        if (outcomeKey == null) {
            outcomeKey = "UNKNOWN";
        }

        String outcomeDesc = getStringField(outcomeNode, "value", "description", "label", "name");
        if (outcomeDesc == null) {
            outcomeDesc = outcomeKey;
        }

        BigDecimal odds = null;
        JsonNode oddsNode = outcomeNode.get("odds");
        if (oddsNode == null) oddsNode = outcomeNode.get("price");
        if (oddsNode == null) oddsNode = outcomeNode.get("coefficient");

        if (oddsNode != null) {
            if (oddsNode.isNumber()) {
                odds = BigDecimal.valueOf(oddsNode.asDouble());
            } else if (oddsNode.isTextual()) {
                try {
                    odds = new BigDecimal(oddsNode.asText().replace(",", "."));
                } catch (Exception e) {
                    // Подавляем
                }
            }
        }

        boolean isActive = true;
        JsonNode activeNode = outcomeNode.get("isActive");
        if (activeNode != null && activeNode.isBoolean()) {
            isActive = activeNode.asBoolean();
        }

        if (odds == null) {
            return null;
        }

        return RawOutcome.builder()
                .externalMarketId(marketId)
                .outcomeKeyName(outcomeKey)
                .outcomeValueDescription(outcomeDesc)
                .odds(odds)
                .isActive(isActive)
                .build();
    }

    private String cleanTeamName(String name) {
        if (name == null) return null;
        name = name.replaceAll("\\([^)]*\\)", "").trim();
        name = name.replaceAll("\\s+", " ").trim();
        name = name.replaceAll("\\s+[Uu]\\d{2}$", "");
        name = name.replaceAll("\\s+[2-3]$", "");
        name = name.replaceAll("\\s+[Бб]$", "");
        return name;
    }

    private String normalizeMarketType(String type) {
        if (type == null) return "WIN_DRAW_WIN";

        String upper = type.toUpperCase();
        if (upper.contains("1X2") || upper.contains("WIN_DRAW_WIN") || upper.contains("RESULT")) {
            return "WIN_DRAW_WIN";
        }
        if (upper.contains("HANDICAP") || upper.contains("FORA")) {
            return "HANDICAP";
        }
        if (upper.contains("TOTAL") || upper.contains("GOALS")) {
            return "TOTAL_GOALS";
        }
        if (upper.contains("DOUBLE")) {
            return "DOUBLE_CHANCE";
        }
        return type;
    }

    private String decompressGzip(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis)) {
            return new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private Map<Integer, String> buildLeagueMapping(JsonNode response) {
        Map<Integer, String> leagueMap = new HashMap<>();
        JsonNode sportsArray = response.get("sports");

        if (sportsArray != null && sportsArray.isArray()) {
            for (JsonNode sportNode : sportsArray) {
                if (sportNode.has("kind") && "segment".equals(sportNode.get("kind").asText())) {
                    int leagueId = sportNode.get("id").asInt();
                    String leagueName = sportNode.get("name").asText();
                    leagueMap.put(leagueId, leagueName);
                }
            }
        }
        return leagueMap;
    }
}