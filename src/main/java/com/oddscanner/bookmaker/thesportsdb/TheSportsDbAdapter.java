package com.oddscanner.bookmaker.thesportsdb;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

//@Component
public class TheSportsDbAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(TheSportsDbAdapter.class);
    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @Override
    public String code() {
        return "thesportsdb";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА TheSportsDB ===");
        List<RawEvent> events = new ArrayList<>();

        try {
            // Получаем ближайшие события английской премьер-лиги (league ID 4328)
            String url = "https://www.thesportsdb.com/api/v1/json/3/eventsnextleague.php?id=4328";
            String response = restTemplate.getForObject(url, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode eventsNode = root.path("events");

            for (JsonNode eventNode : eventsNode) {
                String id = eventNode.path("idEvent").asText();
                String homeTeam = eventNode.path("strHomeTeam").asText();
                String awayTeam = eventNode.path("strAwayTeam").asText();
                String dateStr = eventNode.path("dateEvent").asText();
                String timeStr = eventNode.path("strTime").asText();

                LocalDateTime startTime = LocalDateTime.parse(dateStr + "T" + timeStr,
                        DateTimeFormatter.ISO_LOCAL_DATE_TIME);
//TODO
                RawEvent event = new RawEvent(
//                        id, homeTeam, awayTeam, startTime,
//                        "Football", "Premier League", new ArrayList<>()
                );
                events.add(event);
                log.debug("Событие: {} vs {} (ID: {})", homeTeam, awayTeam, id);
            }

        } catch (Exception e) {
            log.error("Ошибка парсинга TheSportsDB", e);
        }

        log.info("=== TheSportsDB: Найдено {} событий ===", events.size());
        return events;
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        // Для теста возвращаем заглушку
        List<RawMarket> markets = new ArrayList<>();
        List<RawOutcome> outcomes = new ArrayList<>();
        outcomes.add(new RawOutcome("outcome_1", "HOME_WIN", "1", new BigDecimal("2.0"), true));
        outcomes.add(new RawOutcome("outcome_2", "DRAW", "X", new BigDecimal("3.5"), true));
        outcomes.add(new RawOutcome("outcome_3", "AWAY_WIN", "2", new BigDecimal("4.0"), true));

        markets.add(new RawMarket(
                "1X2", "FULL_TIME", null,
                externalEventId, externalEventId, null, outcomes
        ));
        return markets;
    }
}