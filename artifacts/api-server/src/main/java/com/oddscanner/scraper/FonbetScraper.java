package com.oddscanner.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.oddscanner.domain.MarketType;
import com.oddscanner.domain.RawEvent;
import com.oddscanner.domain.RawMarket;
import com.oddscanner.domain.RawOutcome;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

@Component
public class FonbetScraper extends BaseScraper {

    private static final String BASE_URL = "https://line.fonbet.ru";
    private static final String SPORTS = "1,2,3,5";

    private static final Map<String, String> SPORT_NAMES = Map.of(
            "1", "football",
            "2", "hockey",
            "3", "basketball",
            "5", "tennis"
    );

    // factor.pt → {marketType, outcomeName}
    private static final Map<Integer, Object[]> FACTOR_MAP = Map.of(
            921, new Object[]{MarketType.ONE_X_TWO, "1"},
            922, new Object[]{MarketType.ONE_X_TWO, "X"},
            923, new Object[]{MarketType.ONE_X_TWO, "2"},
            924, new Object[]{MarketType.DOUBLE_CHANCE, "1X"},
            925, new Object[]{MarketType.DOUBLE_CHANCE, "12"},
            926, new Object[]{MarketType.DOUBLE_CHANCE, "X2"},
            930, new Object[]{MarketType.BOTH_TEAMS_TO_SCORE, "Да"},
            931, new Object[]{MarketType.BOTH_TEAMS_TO_SCORE, "Нет"}
    );

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public FonbetScraper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getSlug() { return "fonbet"; }

    @Override
    public String getName() { return "Фонбет"; }

    @Override
    public List<RawEvent> fetchEvents() throws Exception {
        String url = BASE_URL + "/api/v1/lineevents?lang=ru&sports=" + SPORTS;

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Fonbet API error: " + response.code());
            }
            return parse(objectMapper.readTree(response.body().string()));
        }
    }

    private List<RawEvent> parse(JsonNode root) {
        JsonNode eventsNode = root.path("events");
        JsonNode factorsNode = root.path("customFactors");

        Map<Long, List<JsonNode>> factorsByEvent = new HashMap<>();
        if (factorsNode.isArray()) {
            for (JsonNode f : factorsNode) {
                long eventId = f.path("e").asLong();
                factorsByEvent.computeIfAbsent(eventId, k -> new ArrayList<>()).add(f);
            }
        }

        List<RawEvent> result = new ArrayList<>();
        if (!eventsNode.isArray()) return result;

        for (JsonNode evt : eventsNode) {
            if (evt.path("level").asInt() != 1) continue;
            String team1 = evt.path("team1Name").asText(null);
            String team2 = evt.path("team2Name").asText(null);
            if (team1 == null || team2 == null) continue;

            long id = evt.path("id").asLong();
            String sportId = evt.path("sportId").asText("1");
            List<JsonNode> factors = factorsByEvent.getOrDefault(id, List.of());
            List<RawMarket> markets = buildMarkets(factors);
            if (markets.isEmpty()) continue;

            Instant startsAt;
            try {
                startsAt = Instant.parse(evt.path("startTime").asText());
            } catch (Exception e) {
                startsAt = Instant.now().plusSeconds(3600);
            }

            result.add(new RawEvent(
                    "fonbet:" + id,
                    SPORT_NAMES.getOrDefault(sportId, "unknown"),
                    team1, team2, startsAt, markets
            ));
        }
        return result;
    }

    private List<RawMarket> buildMarkets(List<JsonNode> factors) {
        Map<MarketType, List<RawOutcome>> byType = new LinkedHashMap<>();

        for (JsonNode f : factors) {
            int pt = f.path("pt").asInt();
            Object[] mapped = FACTOR_MAP.get(pt);
            if (mapped == null) continue;

            String val = f.path("v").asText(null);
            if (val == null) continue;
            double odds;
            try { odds = Double.parseDouble(val); } catch (NumberFormatException e) { continue; }
            if (!Double.isFinite(odds) || odds < 1.01) continue;

            MarketType type = (MarketType) mapped[0];
            String outcomeName = (String) mapped[1];
            byType.computeIfAbsent(type, k -> new ArrayList<>()).add(new RawOutcome(outcomeName, odds));
        }

        List<RawMarket> markets = new ArrayList<>();
        byType.forEach((type, outcomes) ->
                markets.add(new RawMarket(type.toDbValue() + ":main", type, type.getDisplayName(), null, outcomes)));
        return markets;
    }
}
