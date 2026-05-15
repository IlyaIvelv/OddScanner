package com.oddscanner.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EventMatcherService {
    private static final Logger log = LoggerFactory.getLogger(EventMatcherService.class);

    // Стоп-слова для нормализации
    private static final Set<String> STOP_WORDS = Set.of("fc", "cf", "club", "team", "united", "city", "utd", "real", "atletico", "inter", "milan", "barcelona", "madrid", "london", "chelsea", "liverpool", "arsenal", "manchester", "man", "juventus", "roma", "napoli", "psg", "bayern", "dortmund", "leipzig", "ajax", "porto", "benfica", "celtic", "rangers");

    // Удаляет стоп-слова и нормализует строку
    private String normalizeTeamName(String name) {
        if (name == null) return "";
        String normalized = name.toLowerCase()
                .replaceAll("[^a-zа-я0-9\\s]", "")  // убираем знаки препинания
                .replaceAll("\\s+", " ")            // убираем лишние пробелы
                .trim();

        // Убираем стоп-слова
        for (String stopWord : STOP_WORDS) {
            normalized = normalized.replaceAll("\\b" + stopWord + "\\b", "");
        }

        return normalized.trim();
    }

    // Вычисляет схожесть двух строк (Jaccard индекс)
    private double similarity(String s1, String s2) {
        if (s1.isEmpty() || s2.isEmpty()) return 0.0;

        Set<String> set1 = Arrays.stream(s1.split(" ")).collect(Collectors.toSet());
        Set<String> set2 = Arrays.stream(s2.split(" ")).collect(Collectors.toSet());

        if (set1.isEmpty() || set2.isEmpty()) return 0.0;

        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);

        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);

        return (double) intersection.size() / union.size();
    }

    // Матчит события по командам
    public boolean isSameEvent(String home1, String away1, String home2, String away2) {
        String normHome1 = normalizeTeamName(home1);
        String normAway1 = normalizeTeamName(away1);
        String normHome2 = normalizeTeamName(home2);
        String normAway2 = normalizeTeamName(away2);

        // Проверяем прямое совпадение
        if (normHome1.equals(normHome2) && normAway1.equals(normAway2)) {
            return true;
        }

        // Проверяем с перестановкой (если перепутали хозяев/гостей)
        if (normHome1.equals(normAway2) && normAway1.equals(normHome2)) {
            return true;
        }

        // Проверяем по схожести (порог 0.7)
        double homeSimilarity = Math.max(similarity(normHome1, normHome2), similarity(normHome1, normAway2));
        double awaySimilarity = Math.max(similarity(normAway1, normAway2), similarity(normAway1, normHome2));

        return homeSimilarity >= 0.7 && awaySimilarity >= 0.7;
    }
}