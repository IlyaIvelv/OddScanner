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
import java.util.*;
import java.util.stream.Collectors;

@Component
public class PolymarketParser extends AbstractBookmakerParser {

    private static final String API_BASE_URL = "https://gamma-api.polymarket.com";
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    // HARDCODED список событий ЧМ-2026 из твоих JSON
    private static final Map<String, String> WORLD_CUP_EVENTS = new LinkedHashMap<>();
    static {
        // Основные матчи
        WORLD_CUP_EVENTS.put("643884", "Switzerland vs. Algeria");
        WORLD_CUP_EVENTS.put("643876", "Spain vs. Austria");
        // More Markets
        WORLD_CUP_EVENTS.put("643975", "Switzerland vs. Algeria - More Markets");
        WORLD_CUP_EVENTS.put("643977", "Spain vs. Austria - More Markets");

        // TODO: Добавь сюда все ID событий, которые найдешь на сайте!
        // WORLD_CUP_EVENTS.put("ID", "Название матча");
    }

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

        log.info("[Polymarket] Начинаю парсинг ЧМ-2026...");
        log.info("[Polymarket] Загружено {} hardcoded событий", WORLD_CUP_EVENTS.size());

        // Используем ТОЛЬКО hardcoded список
        for (Map.Entry<String, String> entry : WORLD_CUP_EVENTS.entrySet()) {
            String eventId = entry.getKey();
            String title = entry.getValue();

            try {
                log.info("[Polymarket] Парсинг события: {} (ID: {})", title, eventId);

                JsonNode eventNode = fetchEventDetails(eventId);
                if (eventNode == null) {
                    log.warn("[Polymarket] Не удалось получить событие {}", eventId);
                    continue;
                }

                RawEvent event = parseEventWithMarkets(eventNode);
                if (event != null && !event.markets().isEmpty()) {
                    allEvents.add(event);
                    log.info("[Polymarket] ✅ Добавлено событие: {} ({} рынков)", title, event.markets().size());
                } else {
                    log.warn("[Polymarket] ❌ Не удалось распарсить событие: {}", title);
                }

                Thread.sleep(500); // пауза чтобы не забанили

            } catch (Exception e) {
                log.error("[Polymarket] Ошибка парсинга события {}: {}", title, e.getMessage());
            }
        }

        log.info("[Polymarket] Итого найдено спортивных событий: {}", allEvents.size());

        if (!allEvents.isEmpty()) {
            eventRepository.saveEvents("Polymarket", allEvents);
            Set<String> activeExternalIds = allEvents.stream()
                    .map(RawEvent::externalId)
                    .collect(Collectors.toSet());
            eventRepository.markInactiveEvents("Polymarket", activeExternalIds);
            log.info("[Polymarket] Сохранено {} спортивных событий", allEvents.size());
        } else {
            log.warn("[Polymarket] Не найдено спортивных событий");
        }

        return allEvents;
    }

    /**
     * Получает детальную информацию о событии
     */
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

    /**
     * Парсит событие с рынками
     */
    private RawEvent parseEventWithMarkets(JsonNode eventNode) {
        try {
            String title = eventNode.path("title").asText();
            String eventId = eventNode.path("id").asText();
            String slug = eventNode.path("slug").asText();

            log.info("[Polymarket] Парсинг: {}", title);

            // Извлекаем команды
            String[] teams = extractTeamsFromEvent(eventNode);
            String team1 = teams[0];
            String team2 = teams[1];

            if (team1.equals("Unknown") || team2.equals("Unknown")) {
                log.warn("[Polymarket] Не удалось извлечь команды для: {}", title);
                return null;
            }

            // Парсим рынки
            List<RawEvent.RawMarket> markets = parseMarketsFromEvent(eventNode);
            if (markets.isEmpty()) {
                log.warn("[Polymarket] Нет рынков для: {}", title);
                return null;
            }

            String league = "World Cup";
            LocalDateTime startTime = parseStartDate(eventNode);
            String url = "https://polymarket.com/event/" + slug;

            log.info("[Polymarket] Команды: {} vs {}, рынков: {}", team1, team2, markets.size());

            return new RawEvent(
                    eventId,
                    "Football",
                    league,
                    team1,
                    team2,
                    startTime,
                    markets,
                    url
            );
        } catch (Exception e) {
            log.error("[Polymarket] Ошибка парсинга события: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Извлекает команды из события
     */
    private String[] extractTeamsFromEvent(JsonNode eventNode) {
        // 1. Пробуем извлечь из teams
        JsonNode teamsNode = eventNode.path("teams");
        if (teamsNode.isArray() && teamsNode.size() >= 2) {
            String team1 = teamsNode.get(0).path("name").asText();
            String team2 = teamsNode.get(1).path("name").asText();
            if (!team1.isEmpty() && !team2.isEmpty() && !team1.equals("null") && !team2.equals("null")) {
                return new String[]{team1, team2};
            }
        }

        // 2. Пробуем извлечь из title
        String title = eventNode.path("title").asText();
        if (title.contains(" vs ")) {
            String[] parts = title.split(" vs ");
            if (parts.length >= 2) {
                String team1 = parts[0].trim();
                String team2 = parts[1].trim();
                if (!team1.isEmpty() && !team2.isEmpty()) {
                    return new String[]{team1, team2};
                }
            }
        }

        // 3. Пробуем извлечь из markets - ищем команды в вопросах
        JsonNode marketsNode = eventNode.path("markets");
        if (marketsNode.isArray()) {
            List<String> foundTeams = new ArrayList<>();
            for (JsonNode market : marketsNode) {
                String question = market.path("question").asText();
                // Ищем паттерны с командами
                for (String team : Arrays.asList("Switzerland", "Algeria", "Spain", "Austria",
                        "Brazil", "Argentina", "France", "England", "Germany")) {
                    if (question.contains(team) && !foundTeams.contains(team)) {
                        foundTeams.add(team);
                    }
                }
                if (foundTeams.size() >= 2) {
                    return new String[]{foundTeams.get(0), foundTeams.get(1)};
                }
            }
        }

        return new String[]{"Unknown", "Unknown"};
    }

    /**
     * Парсит рынки из события
     */
    private List<RawEvent.RawMarket> parseMarketsFromEvent(JsonNode eventNode) {
        List<RawEvent.RawMarket> markets = new ArrayList<>();

        JsonNode marketsNode = eventNode.path("markets");
        if (!marketsNode.isArray() || marketsNode.size() == 0) {
            return markets;
        }

        log.info("[Polymarket] Найдено {} рынков", marketsNode.size());

        for (JsonNode marketNode : marketsNode) {
            try {
                String question = marketNode.path("question").asText();
                log.debug("[Polymarket] Рынок: {}", question);

                // Пропускаем вопросы с "Will" (это ставки Да/Нет)
                if (question.toLowerCase().contains("will") && !question.toLowerCase().contains("vs")) {
                    continue;
                }

                List<String> outcomes = parseJsonArray(marketNode.path("outcomes"));
                List<String> prices = findPrices(marketNode);

                if (outcomes.isEmpty() || prices.isEmpty() || outcomes.size() != prices.size()) {
                    continue;
                }

                List<RawEvent.RawOutcome> rawOutcomes = new ArrayList<>();
                for (int i = 0; i < outcomes.size(); i++) {
                    try {
                        String outcome = outcomes.get(i).trim();

                        // Пропускаем Yes/No
                        if (outcome.equalsIgnoreCase("yes") || outcome.equalsIgnoreCase("no") ||
                                outcome.equalsIgnoreCase("will") || outcome.equalsIgnoreCase("won't") ||
                                outcome.equalsIgnoreCase("true") || outcome.equalsIgnoreCase("false") ||
                                outcome.matches("\\d+") || outcome.length() < 2) {
                            continue;
                        }

                        double price = Double.parseDouble(prices.get(i));
                        BigDecimal odds = convertPrice(price);
                        rawOutcomes.add(new RawEvent.RawOutcome(outcome, odds));
                    } catch (Exception e) {
                        // Пропускаем
                    }
                }

                if (!rawOutcomes.isEmpty()) {
                    String type = determineMarketType(question, outcomes);
                    markets.add(new RawEvent.RawMarket(type, rawOutcomes));
                }

            } catch (Exception e) {
                log.debug("[Polymarket] Ошибка парсинга рынка: {}", e.getMessage());
            }
        }

        return markets;
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

    private String determineMarketType(String question, List<String> outcomes) {
        String q = question.toLowerCase();

        if (outcomes.size() == 2) {
            String o1 = outcomes.get(0).toLowerCase();
            String o2 = outcomes.get(1).toLowerCase();

            if ((o1.equals("yes") && o2.equals("no")) || (o1.equals("no") && o2.equals("yes"))) {
                if (q.contains("over") || q.contains("under")) {
                    return "Total";
                }
                if (q.contains("both teams")) {
                    return "BothTeamsToScore";
                }
                return "Winner";
            }

            if (o1.contains("over") || o2.contains("over") ||
                    o1.contains("under") || o2.contains("under")) {
                return "Total";
            }

            return "1X2";
        }

        if (outcomes.size() > 2) {
            return "Outright";
        }

        return "Other";
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