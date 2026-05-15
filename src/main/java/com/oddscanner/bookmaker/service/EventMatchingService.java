package com.oddscanner.bookmaker.service;

import com.oddscanner.bookmaker.api.RawEvent;
import org.apache.commons.text.similarity.CosineSimilarity;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class EventMatchingService {

    private final CosineSimilarity cosine = new CosineSimilarity();
    private static final double SIMILARITY_THRESHOLD = 0.6;
    private static final long MAX_TIME_DIFF_MINUTES = 120;

    public boolean isSameMatch(RawEvent event1, RawEvent event2) {
        if (event1 == null || event2 == null) {
            return false;
        }

        // Используем правильные имена методов: getStartTime()
        if (!isTimeMatch(event1.getStartTime(), event2.getStartTime())) {
            return false;
        }

        return areTeamsMatching(event1, event2);
    }

    private boolean isTimeMatch(LocalDateTime time1, LocalDateTime time2) {
        if (time1 == null || time2 == null) {
            return false;
        }
        long minutesDiff = Math.abs(Duration.between(time1, time2).toMinutes());
        return minutesDiff <= MAX_TIME_DIFF_MINUTES;
    }

    private boolean areTeamsMatching(RawEvent event1, RawEvent event2) {
        // Используем правильные имена: getHomeTeamName() и getAwayTeamName()
        String match1 = buildMatchString(event1.getHomeTeamName(), event1.getAwayTeamName());
        String match2 = buildMatchString(event2.getHomeTeamName(), event2.getAwayTeamName());

        Map<CharSequence, Integer> vector1 = createVector(match1);
        Map<CharSequence, Integer> vector2 = createVector(match2);

        double similarity = cosine.cosineSimilarity(vector1, vector2);

        // Можно добавить лог для отладки
        System.out.println("Similarity: " + similarity + " between: " + match1 + " and " + match2);

        return similarity > SIMILARITY_THRESHOLD;
    }

    private String buildMatchString(String homeTeam, String awayTeam) {
        if (homeTeam == null || awayTeam == null) {
            return "";
        }
        return (homeTeam + " " + awayTeam).toLowerCase()
                .replaceAll("[^а-яa-z\\s]", "")
                .replaceAll("\\s+", " ");
    }

    private Map<CharSequence, Integer> createVector(String text) {
        Map<CharSequence, Integer> vector = new HashMap<>();
        if (text == null || text.isEmpty()) {
            return vector;
        }
        String[] words = text.split(" ");
        for (String word : words) {
            if (word.length() > 2) {
                vector.put(word, vector.getOrDefault(word, 0) + 1);
            }
        }
        return vector;
    }
}