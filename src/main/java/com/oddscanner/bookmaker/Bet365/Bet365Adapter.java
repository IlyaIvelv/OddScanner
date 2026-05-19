package com.oddscanner.bookmaker.Bet365;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
public class Bet365Adapter implements BookmakerAdapter {

    private static final String XNET_SYNC_TERM_URL = "http://localhost:5000/bet365";
    private static final String BET365_BASE_URL = "https://www.bet365.com";

    // Популярные футбольные лиги
    private static final List<String> POPULAR_LEAGUES = Arrays.asList(
            "premier-league", "laliga", "bundesliga", "serie-a", "ligue-1", "champions-league"
    );

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();
    private String cachedXNetSyncTerm;
    private long lastTokenFetchTime = 0;

    @Value("${bet365.language:en}")
    private String language;

    @Override
    public String code() {
        return "bet365";
    }

    /**
     * Получает свежий X-Net-Sync-Term из локального Docker-сервиса
     */
    private String getXNetSyncTerm() {
        // Кешируем на 5 минут (soccerapi-server обновляет каждые 10 минут)
        if (cachedXNetSyncTerm != null &&
                System.currentTimeMillis() - lastTokenFetchTime < TimeUnit.MINUTES.toMillis(5)) {
            return cachedXNetSyncTerm;
        }

        try {
            ResponseEntity<String> response = restTemplate.getForEntity(
                    XNET_SYNC_TERM_URL, String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                cachedXNetSyncTerm = response.getBody().trim();
                lastTokenFetchTime = System.currentTimeMillis();
                log.debug("X-Net-Sync-Term обновлён: {}", cachedXNetSyncTerm);
                return cachedXNetSyncTerm;
            }
        } catch (Exception e) {
            log.error("Не удалось получить X-Net-Sync-Term. Запущен ли Docker-контейнер?");
            log.error("Запусти: docker run --rm -it -p 5000:5000 s1m0n38/soccerapi");
        }

        return null;
    }

    /**
     * Делает запрос к Bet365 API с правильными заголовками
     */
    private JsonNode callBet365Api(String endpoint, Map<String, String> params) {
        String xNetSyncTerm = getXNetSyncTerm();
        if (xNetSyncTerm == null) {
            log.error("Нет X-Net-Sync-Term, пропускаем запрос");
            return null;
        }

        // Собираем URL с параметрами
        StringBuilder urlBuilder = new StringBuilder(BET365_BASE_URL + endpoint);
        if (params != null && !params.isEmpty()) {
            urlBuilder.append("?");
            params.forEach((k, v) -> urlBuilder.append(k).append("=").append(v).append("&"));
        }

        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Net-Sync-Term", xNetSyncTerm);
        headers.set("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
        headers.set("Accept", "application/json");
        headers.set("Accept-Language", "en-US,en;q=0.9");
        headers.set("Referer", "https://www.bet365.com/");

        HttpEntity<String> entity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    urlBuilder.toString(), HttpMethod.GET, entity, String.class
            );

            if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
                return mapper.readTree(response.getBody());
            }
        } catch (Exception e) {
            log.debug("Ошибка запроса к Bet365 API: {}", e.getMessage());
        }

        return null;
    }

    @Override
    public List<RawEvent> fetchEvents() {
        List<RawEvent> allEvents = new ArrayList<>();

        // Парсим каждую популярную лигу
        for (String league : POPULAR_LEAGUES) {
            try {
                List<RawEvent> leagueEvents = fetchEventsByLeague(league);
                allEvents.addAll(leagueEvents);
                log.info("Bet365: загружено {} событий из лиги {}", leagueEvents.size(), league);

                // Небольшая задержка между запросами лиг
                Thread.sleep(500);
            } catch (Exception e) {
                log.error("Ошибка загрузки лиги {}: {}", league, e.getMessage());
            }
        }

        log.info("Bet365: всего загружено {} уникальных событий", allEvents.size());
        return allEvents;
    }

    /**
     * Загружает события по конкретной лиге
     */
    private List<RawEvent> fetchEventsByLeague(String leagueId) {
        List<RawEvent> events = new ArrayList<>();

        // Эндпоинт для получения событий лиги (нужно уточнить в Network)
        Map<String, String> params = new HashMap<>();
        params.put("lang", language);
        params.put("leagueId", leagueId);

        JsonNode response = callBet365Api("/api/sports/events", params);

        if (response == null) {
            return events;
        }

        // Парсим ответ (структуру нужно подстроить под реальный API)
        if (response.has("data") && response.get("data").isArray()) {
            for (JsonNode eventNode : response.get("data")) {
                RawEvent event = parseEventFromJson(eventNode);
                if (event != null) {
                    events.add(event);
                }
            }
        }

        return events;
    }

    /**
     * Парсит одно событие из JSON
     */
    private RawEvent parseEventFromJson(JsonNode node) {
        try {
            RawEvent event = new RawEvent();
            event.setExternalId(node.path("id").asText());
            event.setHomeTeamName(node.path("homeTeam").path("name").asText());
            event.setAwayTeamName(node.path("awayTeam").path("name").asText());
            event.setLeagueName(node.path("league").path("name").asText());
            event.setSportName("Football");

            // Парсим время начала
            String startTimeStr = node.path("startTime").asText();
            if (!startTimeStr.isEmpty()) {
                event.setStartTime(LocalDateTime.parse(startTimeStr));
            } else {
                event.setStartTime(LocalDateTime.now().plusDays(7));
            }

            // Парсим коэффициенты 1X2
            List<RawMarket> markets = new ArrayList<>();
            RawMarket mainMarket = new RawMarket();
            mainMarket.setExternalId(event.getExternalId() + "_1X2");
            mainMarket.setExternalEventId(event.getExternalId());
            mainMarket.setMarketTypeName("WIN_DRAW_WIN");
            mainMarket.setPeriodName("FULL_TIME");

            List<RawOutcome> outcomes = new ArrayList<>();

            // Коэффициенты (структура зависит от реального API)
            if (node.has("odds")) {
                JsonNode odds = node.get("odds");
                outcomes.add(createOutcome("HOME_WIN", odds.path("homeWin").asDouble()));
                outcomes.add(createOutcome("DRAW", odds.path("draw").asDouble()));
                outcomes.add(createOutcome("AWAY_WIN", odds.path("awayWin").asDouble()));
            }

            mainMarket.setOutcomes(outcomes);
            markets.add(mainMarket);
            event.setMarkets(markets);

            return event;
        } catch (Exception e) {
            log.debug("Ошибка парсинга события: {}", e.getMessage());
            return null;
        }
    }

    private RawOutcome createOutcome(String key, double oddsValue) {
        RawOutcome outcome = new RawOutcome();
        outcome.setOutcomeKeyName(key);
        outcome.setOutcomeValueDescription(key);
        outcome.setOdds(BigDecimal.valueOf(oddsValue));
        outcome.setActive(true);
        return outcome;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}