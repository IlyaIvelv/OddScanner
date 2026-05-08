package com.oddscanner.service;

import com.oddscanner.domain.MatchedEvent;
import com.oddscanner.domain.RawEvent;
import com.oddscanner.domain.RawMarket;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class EventMatcher {

    private static final Map<String, String> TEAM_ALIASES = Map.ofEntries(
            Map.entry("зенит", "зенит"), Map.entry("zenit", "зенит"),
            Map.entry("цска", "цска"), Map.entry("cska", "цска"),
            Map.entry("спартак", "спартак"), Map.entry("spartak", "спартак"),
            Map.entry("динамо", "динамо"), Map.entry("dynamo", "динамо"),
            Map.entry("локомотив", "локомотив"), Map.entry("lokomotiv", "локомотив"),
            Map.entry("man city", "манчестер сити"),
            Map.entry("man utd", "манчестер юнайтед"),
            Map.entry("real madrid", "реал мадрид")
    );

    public record ScrapeResult(long bookmakerId, List<RawEvent> events) {}

    public List<MatchedEvent> match(List<ScrapeResult> results) {
        if (results.size() < 2) return List.of();

        ScrapeResult base = results.get(0);
        List<MatchedEvent> matched = new ArrayList<>();

        for (RawEvent baseEvent : base.events()) {
            Map<Long, List<RawMarket>> marketsByBookmaker = new LinkedHashMap<>();
            marketsByBookmaker.put(base.bookmakerId(), baseEvent.getMarkets());

            for (int i = 1; i < results.size(); i++) {
                ScrapeResult other = results.get(i);
                other.events().stream()
                        .filter(e -> eventsSimilar(baseEvent, e))
                        .findFirst()
                        .ifPresent(e -> marketsByBookmaker.put(other.bookmakerId(), e.getMarkets()));
            }

            if (marketsByBookmaker.size() >= 2) {
                matched.add(new MatchedEvent(
                        0L,
                        baseEvent.getHomeTeam(),
                        baseEvent.getAwayTeam(),
                        baseEvent.getSport(),
                        baseEvent.getStartsAt(),
                        marketsByBookmaker
                ));
            }
        }
        return matched;
    }

    private boolean eventsSimilar(RawEvent a, RawEvent b) {
        if (!a.getSport().equals(b.getSport())) return false;
        long timeDiff = Math.abs(a.getStartsAt().toEpochMilli() - b.getStartsAt().toEpochMilli());
        if (timeDiff > 3 * 60 * 60 * 1000L) return false;
        return teamsSimilar(a.getHomeTeam(), b.getHomeTeam()) &&
               teamsSimilar(a.getAwayTeam(), b.getAwayTeam());
    }

    private boolean teamsSimilar(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.equals(nb)) return true;
        if (na.contains(nb) || nb.contains(na)) return true;
        int dist = levenshtein(na, nb);
        return (double) dist / Math.max(na.length(), nb.length()) < 0.25;
    }

    private String normalize(String name) {
        String lower = name.toLowerCase().trim();
        String aliased = TEAM_ALIASES.get(lower);
        if (aliased != null) return aliased;
        return lower.replaceAll("\\s+fc$", "").replaceAll("^fc\\s+", "").trim();
    }

    private int levenshtein(String a, String b) {
        int m = a.length(), n = b.length();
        int[][] dp = new int[m + 1][n + 1];
        for (int i = 0; i <= m; i++) dp[i][0] = i;
        for (int j = 0; j <= n; j++) dp[0][j] = j;
        for (int i = 1; i <= m; i++) {
            for (int j = 1; j <= n; j++) {
                dp[i][j] = a.charAt(i - 1) == b.charAt(j - 1)
                        ? dp[i - 1][j - 1]
                        : 1 + Math.min(dp[i - 1][j], Math.min(dp[i][j - 1], dp[i - 1][j - 1]));
            }
        }
        return dp[m][n];
    }
}
