package com.oddscanner.scanner;

import com.oddscanner.bookmaker.api.RawEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EventMatchingService {

    private static final Logger log = LoggerFactory.getLogger(EventMatchingService.class);

    private static final int MAX_TIME_DIFF_MINUTES = 30;
    private static final double MIN_NAME_SIMILARITY = 0.7;

    /**
     * Матчит события из двух списков по командам и времени
     */
    public List<MatchedEventPair> matchEvents(List<RawEvent> events1, List<RawEvent> events2) {
        List<MatchedEventPair> matches = new ArrayList<>();
        Set<Integer> matchedIdx1 = new HashSet<>();
        Set<Integer> matchedIdx2 = new HashSet<>();

        // Нормализуем названия команд
        List<NormalizedEvent> norm1 = new ArrayList<>();
        for (int i = 0; i < events1.size(); i++) {
            RawEvent e = events1.get(i);
            norm1.add(new NormalizedEvent(i, e,
                    normalizeTeamName(e.getHomeTeamName()),
                    normalizeTeamName(e.getAwayTeamName())));
        }

        List<NormalizedEvent> norm2 = new ArrayList<>();
        for (int i = 0; i < events2.size(); i++) {
            RawEvent e = events2.get(i);
            norm2.add(new NormalizedEvent(i, e,
                    normalizeTeamName(e.getHomeTeamName()),
                    normalizeTeamName(e.getAwayTeamName())));
        }

        // Группируем по часу для оптимизации
        Map<Integer, List<NormalizedEvent>> byHour1 = norm1.stream()
                .collect(Collectors.groupingBy(n -> n.event.getStartTime().getHour()));
        Map<Integer, List<NormalizedEvent>> byHour2 = norm2.stream()
                .collect(Collectors.groupingBy(n -> n.event.getStartTime().getHour()));

        for (Map.Entry<Integer, List<NormalizedEvent>> entry : byHour1.entrySet()) {
            int hour = entry.getKey();
            List<NormalizedEvent> hourEvents1 = entry.getValue();

            // Собираем события из этого часа и соседних (±1 час)
            List<NormalizedEvent> candidates = new ArrayList<>();
            candidates.addAll(byHour2.getOrDefault(hour, new ArrayList<>()));
            candidates.addAll(byHour2.getOrDefault(hour - 1, new ArrayList<>()));
            candidates.addAll(byHour2.getOrDefault(hour + 1, new ArrayList<>()));

            for (NormalizedEvent n1 : hourEvents1) {
                if (matchedIdx1.contains(n1.index)) continue;

                NormalizedEvent bestMatch = null;
                double bestScore = 0;

                for (NormalizedEvent n2 : candidates) {
                    if (matchedIdx2.contains(n2.index)) continue;

                    double score = calculateScore(n1, n2);
                    if (score > bestScore && score > MIN_NAME_SIMILARITY) {
                        bestScore = score;
                        bestMatch = n2;
                    }
                }

                if (bestMatch != null) {
                    matches.add(new MatchedEventPair(
                            n1.event, bestMatch.event, bestScore,
                            Math.abs(ChronoUnit.MINUTES.between(n1.event.getStartTime(), bestMatch.event.getStartTime()))
                    ));
                    matchedIdx1.add(n1.index);
                    matchedIdx2.add(bestMatch.index);
                    log.debug("Сматчено: {} vs {} [{}] с {} vs {} [{}] score={}",
                            n1.event.getHomeTeamName(), n1.event.getAwayTeamName(),
                            n1.event.getLeagueName(),
                            bestMatch.event.getHomeTeamName(), bestMatch.event.getAwayTeamName(),
                            bestMatch.event.getLeagueName(), bestScore);
                }
            }
        }

        log.info("Всего сматчено {} пар событий", matches.size());
        return matches;
    }

    private double calculateScore(NormalizedEvent e1, NormalizedEvent e2) {
        long timeDiff = Math.abs(ChronoUnit.MINUTES.between(e1.event.getStartTime(), e2.event.getStartTime()));
        if (timeDiff > MAX_TIME_DIFF_MINUTES) return 0;

        double teamScore;
        // Прямое совпадение
        if (e1.normHome.equals(e2.normHome) && e1.normAway.equals(e2.normAway)) {
            teamScore = 1.0;
        }
        // Перепутаны местами
        else if (e1.normHome.equals(e2.normAway) && e1.normAway.equals(e2.normHome)) {
            teamScore = 0.9;
        }
        else {
            double homeSim = similarity(e1.normHome, e2.normHome);
            double awaySim = similarity(e1.normAway, e2.normAway);
            double swapSim = Math.max(
                    similarity(e1.normHome, e2.normAway) * 0.8,
                    similarity(e1.normAway, e2.normHome) * 0.8
            );
            teamScore = Math.max((homeSim + awaySim) / 2, swapSim);
        }

        double timeBonus = 1.0 - (timeDiff / (double) MAX_TIME_DIFF_MINUTES);
        return teamScore * 0.8 + timeBonus * 0.2;
    }

    private String normalizeTeamName(String name) {
        if (name == null) return "";
        return name.toLowerCase()
                .replaceAll("[^а-яa-z0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private double similarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0;
        if (s1.equals(s2)) return 1.0;
        if (s1.isEmpty() || s2.isEmpty()) return 0;

        int maxLen = Math.max(s1.length(), s2.length());
        int distance = levenshtein(s1, s2);
        return 1.0 - (double) distance / maxLen;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i-1) == b.charAt(j-1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i-1][j] + 1, dp[i][j-1] + 1), dp[i-1][j-1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    private static class NormalizedEvent {
        int index;
        RawEvent event;
        String normHome;
        String normAway;

        NormalizedEvent(int index, RawEvent event, String normHome, String normAway) {
            this.index = index;
            this.event = event;
            this.normHome = normHome;
            this.normAway = normAway;
        }
    }

    public static class MatchedEventPair {
        private final RawEvent event1;
        private final RawEvent event2;
        private final double score;
        private final long timeDiffMinutes;

        public MatchedEventPair(RawEvent event1, RawEvent event2, double score, long timeDiffMinutes) {
            this.event1 = event1;
            this.event2 = event2;
            this.score = score;
            this.timeDiffMinutes = timeDiffMinutes;
        }

        public RawEvent getEvent1() { return event1; }
        public RawEvent getEvent2() { return event2; }
        public double getScore() { return score; }
        public long getTimeDiffMinutes() { return timeDiffMinutes; }
    }
}