// src/main/java/com/oddscanner/bookmaker/fonbet/FonbetApiAdapter.java

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

@Component
public class FonbetAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(FonbetAdapter.class);
    private static final String BASE_URL = "https://fon.bet";

    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, List<RawMarket>> marketsCache = new HashMap<>();

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
            log.info("API URL: {}", apiUrl);

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

    private String discoverApiBaseUrl() {
        log.info("Обнаружение API базового URL через Playwright...");

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Page page = browser.newPage();

            page.setExtraHTTPHeaders(Map.of(
                    "Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
                    "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            ));

            log.info("Загружаем главную страницу: {}", BASE_URL);
            page.navigate(BASE_URL);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            // Перехватываем сетевые запросы
            List<String> apiUrls = new ArrayList<>();
            page.onRequest(request -> {
                String url = request.url();
                if (url.contains("/ma/events/list") && url.contains("bk6bba-resources")) {
                    String base = url.replaceAll("/ma/events/list\\?.*$", "");
                    if (!apiUrls.contains(base)) {
                        apiUrls.add(base);
                        log.debug("Найден API запрос: {}", base);
                    }
                }
            });

            // Перезагружаем страницу
            page.reload();
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            if (!apiUrls.isEmpty()) {
                page.close();
                browser.close();
                return apiUrls.get(0);
            }

            page.close();
            browser.close();

        } catch (Exception e) {
            log.error("Ошибка при обнаружении API URL: {}", e.getMessage(), e);
        }

        log.warn("Не удалось обнаружить API базовый URL");
        return null;
    }

    private String fetchApiData(String url) throws IOException, InterruptedException {
        log.debug("Запрос к API: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "*/*")
                .header("Accept-Encoding", "gzip, deflate, br")  // КЛЮЧЕВОЙ ЗАГОЛОВОК
                .header("Accept-Language", "ru-RU,ru;q=0.9")
                .header("Origin", BASE_URL)
                .header("Referer", BASE_URL + "/")
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .GET()
                .build();

        // Получаем как байты, а не как строку
        HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());

        if (response.statusCode() == 200) {
            byte[] body = response.body();
            log.info("API запрос успешен, размер: {} bytes", body.length);

            // Распаковываем если нужно
            String contentEncoding = response.headers().firstValue("Content-Encoding").orElse("");
            String responseBody;

            if ("gzip".equals(contentEncoding) || "deflate".equals(contentEncoding)) {
                responseBody = decompressGzip(body);
                log.info("Распакован GZIP ответ, размер: {} bytes", responseBody.length());
            } else if ("br".equals(contentEncoding)) {
                responseBody = decompressBrotli(body);
                log.info("Распакован Brotli ответ, размер: {} bytes", responseBody.length());
            } else {
                responseBody = new String(body, java.nio.charset.StandardCharsets.UTF_8);
            }

            return responseBody;
        }

        log.warn("API запрос вернул статус: {}", response.statusCode());
        return null;
    }

    private List<RawEvent> parseApiResponse(String jsonResponse) {
        List<RawEvent> events = new ArrayList<>();

        try {
            JsonNode root = mapper.readTree(jsonResponse);
            JsonNode eventsNode = findEventsNode(root);

            if (eventsNode == null || !eventsNode.isArray()) {
                log.error("Не найден массив событий в ответе API");
                return events;
            }

            log.info("Найдено {} событий в ответе API", eventsNode.size());

            for (JsonNode eventNode : eventsNode) {
                try {
                    RawEvent event = parseEventNode(eventNode);
                    if (event != null && event.getHomeTeamName() != null && event.getAwayTeamName() != null) {
                        events.add(event);
                        log.debug("Добавлено событие: {} vs {}", event.getHomeTeamName(), event.getAwayTeamName());
                    }
                } catch (Exception e) {
                    log.debug("Ошибка парсинга события: {}", e.getMessage());
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
                log.debug("Найдены события по пути: {}", path);
                return node;
            }
        }

        if (root.isArray() && root.size() > 0) {
            log.debug("Корневой узел является массивом событий");
            return root;
        }

        Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> field = fields.next();
            JsonNode value = field.getValue();
            if (value.isArray() && value.size() > 0) {
                JsonNode first = value.get(0);
                if (first.has("homeTeam") || first.has("team1") || first.has("participants")) {
                    log.debug("Найдены события по полю: {}", field.getKey());
                    return value;
                }
            }
        }

        return null;
    }

    private RawEvent parseEventNode(JsonNode eventNode) {
        String eventId = getStringField(eventNode, "id", "eventId", "externalId");
        if (eventId == null || eventId.isEmpty()) {
            eventId = "fonbet_" + UUID.randomUUID();
        }

        String homeTeam = parseHomeTeam(eventNode);
        String awayTeam = parseAwayTeam(eventNode);

        if (homeTeam == null || awayTeam == null) {
            return null;
        }

        LocalDateTime startTime = parseStartTime(eventNode);
        String league = parseLeague(eventNode);

        List<RawMarket> markets = parseMarkets(eventNode, eventId);
        marketsCache.put(eventId, markets);

        RawEvent event = new RawEvent();
        event.setExternalId(eventId);
        event.setHomeTeamName(homeTeam);
        event.setAwayTeamName(awayTeam);
        event.setStartTime(startTime != null ? startTime : LocalDateTime.now().plusDays(7));
        event.setLeagueName(league != null ? league : "Unknown League");
        event.setSportName("Football");
        event.setEventUrl(BASE_URL + "/live/football");
        event.setMarkets(markets);

        return event;
    }

    private String parseHomeTeam(JsonNode eventNode) {
        String[] fieldNames = {"homeTeam", "team1", "home", "participant1", "homeName"};
        for (String name : fieldNames) {
            String team = getStringField(eventNode, name);
            if (team != null && !team.isEmpty()) {
                return cleanTeamName(team);
            }
        }

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return cleanTeamName(getStringField(participants.get(0), "name", "title"));
        }

        return null;
    }

    private String parseAwayTeam(JsonNode eventNode) {
        String[] fieldNames = {"awayTeam", "team2", "away", "participant2", "awayName"};
        for (String name : fieldNames) {
            String team = getStringField(eventNode, name);
            if (team != null && !team.isEmpty()) {
                return cleanTeamName(team);
            }
        }

        JsonNode participants = eventNode.get("participants");
        if (participants != null && participants.isArray() && participants.size() >= 2) {
            return cleanTeamName(getStringField(participants.get(1), "name", "title"));
        }

        return null;
    }

    private LocalDateTime parseStartTime(JsonNode eventNode) {
        String[] fieldNames = {"startTime", "date", "kickoff", "startDate"};
        for (String name : fieldNames) {
            String timeStr = getStringField(eventNode, name);
            if (timeStr != null && !timeStr.isEmpty()) {
                try {
                    return LocalDateTime.parse(timeStr);
                } catch (Exception e) {
                    log.debug("Не удалось распарсить время: {}", timeStr);
                }
            }
        }
        return null;
    }

    private String parseLeague(JsonNode eventNode) {
        String[] fieldNames = {"league", "tournament", "competition", "category", "leagueName"};
        for (String name : fieldNames) {
            String league = getStringField(eventNode, name);
            if (league != null && !league.isEmpty() && league.length() < 100) {
                return cleanTeamName(league);
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
        String marketId = getStringField(marketNode, "id", "marketId");
        if (marketId == null) {
            marketId = eventId + "_" + UUID.randomUUID();
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
        if (oddsNode == null) {
            oddsNode = outcomeNode.get("price");
        }
        if (oddsNode == null) {
            oddsNode = outcomeNode.get("coefficient");
        }

        if (oddsNode != null) {
            if (oddsNode.isNumber()) {
                odds = BigDecimal.valueOf(oddsNode.asDouble());
            } else if (oddsNode.isTextual()) {
                try {
                    String oddsStr = oddsNode.asText().replace(",", ".");
                    odds = new BigDecimal(oddsStr);
                } catch (Exception e) {
                    log.debug("Не удалось распарсить коэффициент: {}", oddsNode.asText());
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

    private String getStringField(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            JsonNode field = node.get(fieldName);
            if (field != null && !field.isMissingNode() && field.isTextual()) {
                String value = field.asText();
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    private String cleanTeamName(String name) {
        if (name == null) return null;
        return name.replaceAll("\\s+", " ").trim();
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

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return marketsCache.getOrDefault(externalEventId, new ArrayList<>());
    }


    private String decompressGzip(byte[] compressed) throws IOException {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(compressed);
             java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(bis)) {
            return new String(gis.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    private String decompressBrotli(byte[] compressed) {
        try {
            // Brotli через Playwright или отдельную библиотеку
            // Простой вариант - попробовать как gzip
            return decompressGzip(compressed);
        } catch (Exception e) {
            // Если Brotli не получился, пробуем как есть
            log.warn("Не удалось распаковать Brotli, пробуем как обычную строку");
            return new String(compressed, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

}