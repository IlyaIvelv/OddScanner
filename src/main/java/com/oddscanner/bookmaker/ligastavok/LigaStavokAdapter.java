package com.oddscanner.bookmaker.ligastavok;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Component
@Order(4)
public class LigaStavokAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(LigaStavokAdapter.class);
    private static final String API_URL = "https://lds-api-sites.ligastavok.ru/rest/events/v8/eventsList";

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String code() {
        return "ligastavok";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА ЛИГА СТАВОК ===");

        try {
            String requestBody = buildRequestBody();
            String json = fetchEventsJson(requestBody);

            if (json != null) {
                log.info("Получен ответ, длина: {} символов", json.length());
                return parseEvents(json);
            }

        } catch (Exception e) {
            log.error("Ошибка при парсинге: {}", e.getMessage(), e);
        }

        return new ArrayList<>();
    }

    private String buildRequestBody() {
        // TODO: Замени на точный JSON из твоего сниффера
        return """
        {
            "filter": {
                "sportIds": [1],
                "competitionIds": [],
                "eventStatuses": ["LIVE", "PREMATCH"],
                "withMargin": false
            },
            "actionLines": ["LINE", "LIVE"],
            "tournamentTree": false,
            "getEventDate": true
        }
        """;
    }

    private String fetchEventsJson(String requestBody) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                    .header("Origin", "https://www.ligastavok.ru")
                    .header("Referer", "https://www.ligastavok.ru/")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36")
                    .header("x-application-name", "mobile")
                    .header("x-req-id", UUID.randomUUID().toString())
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            log.info("Отправляем POST запрос к: {}", API_URL);
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            log.info("Статус ответа: {}", response.statusCode());

            if (response.statusCode() == 200) {
                log.info("✅ Успешно получены данные");
                return response.body();
            } else {
                log.error("❌ Ошибка API: {}, тело: {}", response.statusCode(), response.body());
                return null;
            }

        } catch (Exception e) {
            log.error("Ошибка при HTTP-запросе: {}", e.getMessage(), e);
            return null;
        }
    }

    private List<RawEvent> parseEvents(String json) {
        List<RawEvent> events = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(json);
            JsonNode dataNode = root.path("result").path("data");

            if (dataNode == null || !dataNode.isArray()) {
                log.warn("Не найден массив data в result");
                return events;
            }

            log.info("Найдено {} событий в ответе API", dataNode.size());

            for (JsonNode eventNode : dataNode) {
                try {
                    RawEvent event = parseSingleEvent(eventNode);
                    if (event != null && event.getHomeTeamName() != null && !event.getHomeTeamName().isEmpty()) {
                        events.add(event);
                    }
                } catch (Exception e) {
                    log.debug("Ошибка парсинга одного события: {}", e.getMessage());
                }
            }

            log.info("Успешно спарсено {} событий", events.size());

        } catch (Exception e) {
            log.error("Ошибка парсинга JSON: {}", e.getMessage(), e);
        }
        return events;
    }


    private RawEvent parseSingleEvent(JsonNode eventNode) {
        try {
            JsonNode eventInfo = eventNode.path("event");

            String homeTeam = "";
            String awayTeam = "";

            if (eventInfo.has("team1") && eventInfo.has("team2")) {
                homeTeam = cleanTeamName(eventInfo.get("team1").asText());
                awayTeam = cleanTeamName(eventInfo.get("team2").asText());
            }

            if (homeTeam.isEmpty() || awayTeam.isEmpty()) {
                return null;
            }

            String eventId = String.valueOf(eventNode.path("id").asLong());
            if (eventId.equals("0")) {
                eventId = String.valueOf(eventNode.path("hash").asLong());
            }

            String league = eventInfo.path("topicTitle").asText();
            String sport = eventNode.path("gameTitle").asText();

            LocalDateTime startTime = LocalDateTime.now().plusDays(7);
            long gameTs = eventNode.path("gameTs").asLong();
            if (gameTs > 0) {
                startTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(gameTs), ZoneId.systemDefault());
            }

            // Парсим коэффициенты - ищем в разных местах
            List<RawOutcome> outcomes = new ArrayList<>();

            // Пробуем найти рынки
            JsonNode marketsNode = null;

            if (eventNode.has("markets") && eventNode.get("markets").isObject()) {
                marketsNode = eventNode.get("markets");
                log.debug("Нашли markets");
            } else if (eventNode.has("factors") && eventNode.get("factors").isObject()) {
                marketsNode = eventNode.get("factors");
                log.debug("Нашли factors");
            } else if (eventNode.has("odds") && eventNode.get("odds").isObject()) {
                marketsNode = eventNode.get("odds");
                log.debug("Нашли odds");
            } else if (eventNode.has("outcomes") && eventNode.get("outcomes").isObject()) {
                marketsNode = eventNode.get("outcomes");
                log.debug("Нашли outcomes");
            }

            if (marketsNode != null) {
                // Перебираем все возможные рынки
                Iterator<Map.Entry<String, JsonNode>> fields = marketsNode.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> entry = fields.next();
                    JsonNode marketNode = entry.getValue();

                    String marketType = marketNode.path("type").asText();
                    log.debug("Найден рынок: type={}", marketType);

                    // Ищем WIN или 1X2 рынок
                    if ("WIN".equals(marketType) || "1X2".equals(marketType) || "CLASSIC".equals(marketType)) {
                        String marketId = marketNode.path("id").asText();
                        JsonNode outcomesNode = marketNode.path("outcomes");

                        if (outcomesNode.isObject()) {
                            Iterator<Map.Entry<String, JsonNode>> outcomeFields = outcomesNode.fields();
                            while (outcomeFields.hasNext()) {
                                Map.Entry<String, JsonNode> outcomeEntry = outcomeFields.next();
                                JsonNode outcomeNode = outcomeEntry.getValue();

                                String outcomeKey = outcomeNode.path("key").asText();
                                double odds = outcomeNode.path("value").asDouble();

                                if (odds > 0) {
                                    String mappedType = null;
                                    if ("1".equals(outcomeKey) || "HOME".equals(outcomeKey)) {
                                        mappedType = "HOME";
                                    } else if ("X".equals(outcomeKey) || "DRAW".equals(outcomeKey)) {
                                        mappedType = "DRAW";
                                    } else if ("2".equals(outcomeKey) || "AWAY".equals(outcomeKey)) {
                                        mappedType = "AWAY";
                                    }

                                    if (mappedType != null) {
                                        outcomes.add(RawOutcome.builder()
                                                .externalMarketId(marketId)
                                                .outcomeKeyName(mappedType)
                                                .outcomeValueDescription("")
                                                .odds(BigDecimal.valueOf(odds))
                                                .isActive(true)
                                                .build());
                                    }
                                }
                            }
                        }
                        break;
                    }
                }
            }

            // Если не нашли через markets, пробуем найти прямые коэффициенты в событии
            if (outcomes.isEmpty()) {
                log.debug("Пробуем найти прямые коэффициенты");

                // Проверяем наличие полей с коэффициентами
                if (eventNode.has("coeff1") && eventNode.has("coeffX") && eventNode.has("coeff2")) {
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("HOME")
                            .odds(BigDecimal.valueOf(eventNode.get("coeff1").asDouble())).isActive(true).build());
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("DRAW")
                            .odds(BigDecimal.valueOf(eventNode.get("coeffX").asDouble())).isActive(true).build());
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("AWAY")
                            .odds(BigDecimal.valueOf(eventNode.get("coeff2").asDouble())).isActive(true).build());
                } else if (eventNode.has("odds1") && eventNode.has("oddsX") && eventNode.has("odds2")) {
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("HOME")
                            .odds(BigDecimal.valueOf(eventNode.get("odds1").asDouble())).isActive(true).build());
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("DRAW")
                            .odds(BigDecimal.valueOf(eventNode.get("oddsX").asDouble())).isActive(true).build());
                    outcomes.add(RawOutcome.builder().externalMarketId("1X2").outcomeKeyName("AWAY")
                            .odds(BigDecimal.valueOf(eventNode.get("odds2").asDouble())).isActive(true).build());
                }
            }

            if (outcomes.size() < 3) {
                log.debug("Недостаточно исходов для {} - {}: {}", homeTeam, awayTeam, outcomes.size());
                return null;
            }

            RawMarket market = RawMarket.builder()
                    .externalId("WIN_DRAW_WIN")
                    .externalEventId(eventId)
                    .marketTypeName("WIN_DRAW_WIN")
                    .periodName("FULL_TIME")
                    .outcomes(outcomes)
                    .build();

            RawEvent event = new RawEvent();
            event.setExternalId("ligastavok_" + eventId);
            event.setHomeTeamName(homeTeam);
            event.setAwayTeamName(awayTeam);
            event.setStartTime(startTime);
            event.setLeagueName(league);
            event.setSportName(mapSport(sport));
            event.setMarkets(List.of(market));

            log.info("✅ Спарсено: {} - {} (П1:{}, X:{}, П2:{})",
                    homeTeam, awayTeam,
                    outcomes.get(0).getOdds(),
                    outcomes.get(1).getOdds(),
                    outcomes.get(2).getOdds());

            return event;

        } catch (Exception e) {
            log.error("Ошибка парсинга события: {}", e.getMessage(), e);
            return null;
        }
    }

    private String cleanTeamName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s-]", "");
    }

    private String mapSport(String sport) {
        if (sport == null) return "Футбол";
        switch (sport.toLowerCase()) {
            case "футбол": return "Футбол";
            case "хоккей": return "Хоккей";
            case "баскетбол": return "Баскетбол";
            case "теннис": return "Теннис";
            case "киберспорт": return "Киберспорт";
            default: return sport;
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}