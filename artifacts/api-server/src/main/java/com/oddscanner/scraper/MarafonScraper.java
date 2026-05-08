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
public class MarafonScraper extends BaseScraper {

    private static final String BASE_URL = "https://www.marathonbet.ru";

    private static final Map<String, MarketType> MARKET_NAME_MAP = Map.of(
            "Результат матча", MarketType.ONE_X_TWO,
            "Исход", MarketType.ONE_X_TWO,
            "Тотал", MarketType.TOTAL_OVER_UNDER,
            "Фора", MarketType.HANDICAP,
            "Угловые", MarketType.CORNERS_TOTAL,
            "Обе забьют", MarketType.BOTH_TEAMS_TO_SCORE,
            "Двойной шанс", MarketType.DOUBLE_CHANCE
    );

    private static final Map<String, String> SPORT_NAME_MAP = Map.of(
            "Футбол", "football", "Football", "football",
            "Хоккей", "hockey", "Hockey", "hockey",
            "Баскетбол", "basketball", "Basketball", "basketball",
            "Теннис", "tennis", "Tennis", "tennis"
    );

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public MarafonScraper(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public String getSlug() { return "marafon"; }

    @Override
    public String getName() { return "Марафон"; }

    @Override
    public List<RawEvent> fetchEvents() throws Exception {
        String url = BASE_URL + "/en/betting/football/?lang=ru&type=json";

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .header("Accept", "application/json")
                .header("X-Requested-With", "XMLHttpRequest")
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new RuntimeException("Marathonbet API error: " + response.code());
            }
            return parse(objectMapper.readTree(response.body().string()));
        }
    }

    private List<RawEvent> parse(JsonNode root) {
        JsonNode eventsNode = root.path("Events");
        List<RawEvent> result = new ArrayList<>();
        if (!eventsNode.isArray()) return result;

        for (JsonNode evt : eventsNode) {
            String memberName = evt.path("EventMemberName").asText(null);
            String eventDate = evt.path("EventDate").asText(null);
            if (memberName == null || eventDate == null) continue;

            String[] teams = parseTeams(memberName);
            if (teams == null) continue;

            String klid = evt.path("KLID").asText("0");
            String sportName = evt.path("SportName").asText("Football");

            List<RawMarket> markets = buildMarkets(evt.path("runners"));
            if (markets.isEmpty()) continue;

            Instant startsAt;
            try { startsAt = Instant.parse(eventDate); } catch (Exception e) { startsAt = Instant.now().plusSeconds(3600); }

            result.add(new RawEvent(
                    "marafon:" + klid,
                    SPORT_NAME_MAP.getOrDefault(sportName, sportName.toLowerCase()),
                    teams[0], teams[1], startsAt, markets
            ));
        }
        return result;
    }

    private String[] parseTeams(String name) {
        String[] parts = name.contains(" - ") ? name.split(" - ", 2) : name.split(" vs ", 2);
        if (parts.length < 2) return null;
        return new String[]{parts[0].trim(), parts[1].trim()};
    }

    private List<RawMarket> buildMarkets(JsonNode runners) {
        Map<String, List<RawOutcome>> grouped = new LinkedHashMap<>();
        if (runners.isArray()) {
            for (JsonNode runner : runners) {
                String group = runner.path("MarketGroupName").asText("unknown");
                String priceStr = runner.path("Price").asText("0");
                double price;
                try { price = Double.parseDouble(priceStr); } catch (NumberFormatException e) { continue; }
                if (!Double.isFinite(price) || price < 1.01) continue;
                grouped.computeIfAbsent(group, k -> new ArrayList<>())
                        .add(new RawOutcome(runner.path("Name").asText(""), price));
            }
        }

        List<RawMarket> markets = new ArrayList<>();
        grouped.forEach((groupName, outcomes) -> {
            MarketType type = resolveMarketType(groupName);
            if (type != null) {
                markets.add(new RawMarket(type.toDbValue() + ":main", type, groupName, null, outcomes));
            }
        });
        return markets;
    }

    private MarketType resolveMarketType(String groupName) {
        for (Map.Entry<String, MarketType> entry : MARKET_NAME_MAP.entrySet()) {
            if (groupName.contains(entry.getKey())) return entry.getValue();
        }
        return null;
    }
}
