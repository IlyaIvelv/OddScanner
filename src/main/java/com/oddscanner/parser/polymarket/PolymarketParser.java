package com.oddscanner.parser.polymarket;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PolymarketParser extends AbstractBookmakerParser {

    private static final String API_BASE_URL = "https://gamma-api.polymarket.com";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // Паттерн для полных названий команд: "France vs. Spain", "Argentina vs Switzerland"
    private static final Pattern FULL_TEAMS_PATTERN = Pattern.compile(
            "([A-Z][a-zA-Z\\s\\-']+?)\\s+vs\\.?\\s+([A-Z][a-zA-Z\\s\\-']+?)(?:\\s+-|\\s*$|\\s+\\?)"
    );

    // Паттерн для сокращений: "FRA vs ENG"
    private static final Pattern SHORT_TEAMS_PATTERN = Pattern.compile(
            "([A-Z]{2,3})\\s+vs\\.?\\s+([A-Z]{2,3})"
    );

    public PolymarketParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
        this.httpClient = HttpClient.newHttpClient();
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public String getName() {
        return "Polymarket";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> allEvents = new ArrayList<>();

        log.info("[Polymarket] Начинаю парсинг...");

        JsonNode eventsArray = fetchEventsList();
        if (eventsArray == null || !eventsArray.isArray()) {
            log.warn("[Polymarket] Не удалось получить список событий");
            return allEvents;
        }

        log.info("[Polymarket] Найдено {} событий в API", eventsArray.size());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime minTime = now.plusHours(1);
        LocalDateTime maxTime = now.plusDays(14);  // Увеличили до 14 дней

        int processedCount = 0;
        int skippedCount = 0;

        for (JsonNode eventNode : eventsArray) {
            try {
                String eventId = eventNode.path("id").asText();
                String title = eventNode.path("title").asText();
                boolean closed = eventNode.path("closed").asBoolean(false);
                boolean active = eventNode.path("active").asBoolean(false);

                if (closed || !active) {
                    skippedCount++;
                    continue;
                }

                if (!isSportsEvent(eventNode)) {
                    skippedCount++;
                    continue;
                }

                LocalDateTime startTime = parseStartDate(eventNode);
                if (startTime.isBefore(minTime) || startTime.isAfter(maxTime)) {
                    skippedCount++;
                    continue;
                }

                // Извлекаем команды из title
                String[] teams = extractTeams(title);
                if (teams == null) {
                    skippedCount++;
                    log.debug("[Polymarket] Не удалось извлечь команды из: {}", title);
                    continue;
                }

                JsonNode details = fetchEventDetails(eventId);
                if (details == null) {
                    skippedCount++;
                    continue;
                }

                List<RawEvent> events = parseRealMatch(details, teams);

                if (!events.isEmpty()) {
                    allEvents.addAll(events);
                    processedCount++;
                    log.info("[Polymarket] ✅ Матч: {} vs {} ({} рынков)",
                            teams[0], teams[1], events.get(0).markets().size());
                } else {
                    skippedCount++;
                }

                Thread.sleep(300);

            } catch (Exception e) {
                log.error("[Polymarket] Ошибка парсинга: {}", e.getMessage());
            }
        }

        log.info("[Polymarket] Спарсено {} событий, {} пропущено", allEvents.size(), skippedCount);

        if (!allEvents.isEmpty()) {
            eventRepository.saveEvents("Polymarket", allEvents);
            Set<String> activeExternalIds = allEvents.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());
            eventRepository.markInactiveEvents("Polymarket", activeExternalIds);
            log.info("[Polymarket] Сохранено {} событий", allEvents.size());
        } else {
            log.warn("[Polymarket] Не найдено подходящих событий");
        }

        return allEvents;
    }

    /**
     * Извлекает команды из title (полные названия или сокращения)
     */
    private String[] extractTeams(String text) {
        if (text == null) return null;

        // Сначала ищем полные названия: "France vs. Spain"
        Matcher matcher = FULL_TEAMS_PATTERN.matcher(text);
        if (matcher.find()) {
            String team1 = matcher.group(1).trim();
            String team2 = matcher.group(2).trim();

            // Убираем лишние слова из team2
            team2 = team2.replaceAll("\\s+-.*$", "").trim();

            if (!team1.isEmpty() && !team2.isEmpty()) {
                return new String[]{team1, team2};
            }
        }

        // Потом ищем сокращения: "FRA vs ENG"
        matcher = SHORT_TEAMS_PATTERN.matcher(text);
        if (matcher.find()) {
            return new String[]{matcher.group(1), matcher.group(2)};
        }

        return null;
    }

    /**
     * Парсит реальный матч
     */
    private List<RawEvent> parseRealMatch(JsonNode eventNode, String[] teams) {
        List<RawEvent> events = new ArrayList<>();

        String eventId = eventNode.path("id").asText();
        String slug = eventNode.path("slug").asText();
        LocalDateTime startTime = parseStartDate(eventNode);

        JsonNode marketsNode = eventNode.path("markets");
        if (!marketsNode.isArray() || marketsNode.isEmpty()) {
            return events;
        }

        List<RawEvent.RawMarket> markets = new ArrayList<>();

        for (JsonNode marketNode : marketsNode) {
            try {
                if (!marketNode.path("active").asBoolean(false) ||
                        marketNode.path("closed").asBoolean(false)) {
                    continue;
                }

                String question = marketNode.path("question").asText();
                List<String> outcomes = parseJsonArray(marketNode.path("outcomes"));
                List<String> prices = findPrices(marketNode);

                if (outcomes.size() != prices.size() || outcomes.isEmpty()) {
                    continue;
                }

                RawEvent.RawMarket market = parseMarket(question, outcomes, prices);
                if (market != null) {
                    markets.add(market);
                }

            } catch (Exception e) {
                log.debug("[Polymarket] Ошибка парсинга рынка: {}", e.getMessage());
            }
        }

        if (!markets.isEmpty()) {
            String url = "https://polymarket.com/event/" + slug;

            RawEvent event = new RawEvent(
                    eventId,
                    "Football",
                    "World Cup",
                    teams[0],
                    teams[1],
                    startTime,
                    markets,
                    url
            );

            events.add(event);
        }

        return events;
    }

    /**
     * Парсит рынок (1X2 или Yes/No)
     */
    private RawEvent.RawMarket parseMarket(String question, List<String> outcomes, List<String> prices) {
        String q = question.toLowerCase();

        // Рынок 1X2: Home/Draw/Away
        if (outcomes.size() == 3) {
            int drawIndex = -1;
            for (int i = 0; i < outcomes.size(); i++) {
                String o = outcomes.get(i).toLowerCase();
                if (o.equals("draw") || o.equals("ничья") || o.equals("tie")) {
                    drawIndex = i;
                    break;
                }
            }

            if (drawIndex != -1) {
                List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                for (int i = 0; i < outcomes.size(); i++) {
                    double price = Double.parseDouble(prices.get(i));
                    if (price > 0.001 && price < 0.999) {
                        BigDecimal odds = convertPrice(price);
                        marketOutcomes.add(new RawEvent.RawOutcome(outcomes.get(i), odds));
                    }
                }

                if (marketOutcomes.size() == 3) {
                    return new RawEvent.RawMarket("1X2", marketOutcomes);
                }
            }
        }

        // Рынок Yes/No или Home/Away
        if (outcomes.size() == 2) {
            String o1 = outcomes.get(0).toLowerCase();
            String o2 = outcomes.get(1).toLowerCase();

            if ((o1.equals("yes") && o2.equals("no")) ||
                    (o1.equals("no") && o2.equals("yes"))) {

                double price1 = Double.parseDouble(prices.get(0));
                double price2 = Double.parseDouble(prices.get(1));

                if (price1 > 0.001 && price1 < 0.999 && price2 > 0.001 && price2 < 0.999) {
                    List<RawEvent.RawOutcome> marketOutcomes = new ArrayList<>();
                    marketOutcomes.add(new RawEvent.RawOutcome(outcomes.get(0), convertPrice(price1)));
                    marketOutcomes.add(new RawEvent.RawOutcome(outcomes.get(1), convertPrice(price2)));

                    String marketType = "MatchWinner";
                    if (q.contains("over") || q.contains("under")) {
                        marketType = "Total";
                    } else if (q.contains("both teams")) {
                        marketType = "BothTeamsToScore";
                    }

                    return new RawEvent.RawMarket(marketType, marketOutcomes);
                }
            }
        }

        return null;
    }

    private boolean isSportsEvent(JsonNode eventNode) {
        JsonNode tagsNode = eventNode.path("tags");
        if (tagsNode.isArray()) {
            for (JsonNode tag : tagsNode) {
                String slug = tag.path("slug").asText();
                if (slug.equals("sports") || slug.equals("soccer") ||
                        slug.equals("football") || slug.equals("fifa-world-cup") ||
                        slug.equals("world-cup")) {
                    return true;
                }
            }
        }
        return false;
    }

    private JsonNode fetchEventsList() throws Exception {
        String[] urls = {
                API_BASE_URL + "/events/keyset?limit=100&tag_slug=fifa-world-cup&closed=false&order=startDate&ascending=true",
                API_BASE_URL + "/events/keyset?limit=100&tag_slug=world-cup&closed=false&order=startDate&ascending=true",
                API_BASE_URL + "/events/keyset?limit=100&tag_slug=soccer&closed=false&order=startDate&ascending=true",
                API_BASE_URL + "/events/keyset?limit=100&tag_slug=football&closed=false&order=startDate&ascending=true"
        };

        for (String url : urls) {
            try {
                log.info("[Polymarket] Пробую endpoint: {}", url);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Accept", "application/json")
                        .GET()
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                log.info("[Polymarket] Status: {}, Response length: {}",
                        response.statusCode(), response.body().length());

                if (response.statusCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.body());

                    JsonNode eventsArray = null;

                    if (json.isArray()) {
                        eventsArray = json;
                    } else if (json.isObject()) {
                        if (json.has("events") && json.path("events").isArray()) {
                            eventsArray = json.path("events");
                        } else if (json.has("data") && json.path("data").isArray()) {
                            eventsArray = json.path("data");
                        }
                    }

                    if (eventsArray != null && eventsArray.size() > 0) {
                        log.info("[Polymarket] ✅ Нашёл {} событий", eventsArray.size());
                        return eventsArray;
                    }
                }

            } catch (Exception e) {
                log.error("[Polymarket] Ошибка запроса к {}: {}", url, e.getMessage());
            }
        }

        log.error("[Polymarket] ❌ Не удалось найти рабочий endpoint");
        return null;
    }

    private JsonNode fetchEventDetails(String eventId) throws Exception {
        String url = API_BASE_URL + "/events/" + eventId;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() == 200) {
            return objectMapper.readTree(response.body());
        }

        return null;
    }

    private List<String> findPrices(JsonNode marketNode) {
        String[] fields = {"outcomePrices", "outcome_prices", "prices", "clobTokenPrices"};

        for (String field : fields) {
            JsonNode node = marketNode.path(field);
            if (!node.isMissingNode() && !node.isNull()) {
                return parseJsonArray(node);
            }
        }

        return new ArrayList<>();
    }

    private BigDecimal convertPrice(double price) {
        if (price <= 0.001) {
            return new BigDecimal("1000.00");
        }
        if (price >= 0.999) {
            return new BigDecimal("1.00");
        }
        return BigDecimal.valueOf(1.0 / price).setScale(2, RoundingMode.HALF_UP);
    }

    private List<String> parseJsonArray(JsonNode node) {
        try {
            if (node.isTextual()) {
                return objectMapper.readValue(node.asText(), new TypeReference<List<String>>() {});
            } else if (node.isArray()) {
                return objectMapper.convertValue(node, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            // Игнорируем
        }
        return new ArrayList<>();
    }

    private LocalDateTime parseStartDate(JsonNode eventNode) {
        String dateStr = eventNode.path("endDate").asText(null);
        if (dateStr == null) {
            dateStr = eventNode.path("startDate").asText(null);
        }
        if (dateStr == null) {
            dateStr = eventNode.path("end_date_iso").asText(null);
        }
        if (dateStr == null) {
            dateStr = eventNode.path("start_date_iso").asText(null);
        }

        if (dateStr != null && !dateStr.isEmpty()) {
            try {
                Instant instant = Instant.parse(dateStr);
                return LocalDateTime.ofInstant(instant, ZoneId.systemDefault());
            } catch (Exception e) {
                // Игнорируем
            }
        }

        return LocalDateTime.now().plusDays(1);
    }
}