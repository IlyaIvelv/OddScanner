package com.oddscanner.scanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class EventMatcherService {
    private static final Logger log = LoggerFactory.getLogger(EventMatcherService.class);

    // Стоп-слова для нормализации команд
    private static final Set<String> TEAM_STOP_WORDS = Set.of(
            "fc", "cf", "club", "team", "united", "utd", "city", "real",
            "atletico", "inter", "milan", "barcelona", "madrid", "london",
            "chelsea", "liverpool", "arsenal", "manchester", "man", "juventus",
            "roma", "napoli", "psg", "bayern", "dortmund", "leipzig", "ajax",
            "porto", "benfica", "celtic", "rangers"
    );

    // Стоп-слова для нормализации лиг
    private static final Set<String> LEAGUE_STOP_WORDS = Set.of(
            "league", "cup", "championship", "tournament", "competition",
            "чемпионат", "кубок", "лига", "турнир", "первенство"
    );

    /**
     * Нормализует название команды
     */
    private String normalizeTeamName(String name) {
        if (name == null || name.isEmpty()) return "";

        String normalized = name.toLowerCase()
                .replaceAll("[^a-zа-я0-9\\s]", "")  // убираем знаки препинания
                .replaceAll("\\s+", " ")            // убираем лишние пробелы
                .trim();

        // Убираем стоп-слова
        for (String stopWord : TEAM_STOP_WORDS) {
            normalized = normalized.replaceAll("\\b" + stopWord + "\\b", "");
        }

        return normalized.trim();
    }

    /**
     * Нормализует название лиги
     */
    private String normalizeLeagueName(String leagueName) {
        if (leagueName == null || leagueName.isEmpty()) return "";

        String normalized = leagueName.toLowerCase()
                .replaceAll("[^a-zа-я0-9\\s]", "")
                .replaceAll("\\s+", " ")
                .trim();

        // Убираем стоп-слова
        for (String stopWord : LEAGUE_STOP_WORDS) {
            normalized = normalized.replaceAll("\\b" + stopWord + "\\b", "");
        }

        return normalized.trim();
    }

    /**
     * Вычисляет Jaccard similarity для двух строк
     */
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

    /**
     * Проверяет, совпадают ли лиги
     */
    private boolean isSameLeague(String league1, String league2) {
        String normLeague1 = normalizeLeagueName(league1);
        String normLeague2 = normalizeLeagueName(league2);

        // Если обе лиги неизвестны — считаем, что потенциально могут совпадать
        if (normLeague1.isEmpty() && normLeague2.isEmpty()) {
            return true;
        }

        // Если одна известна, а другая нет — осторожно пропускаем
        if (normLeague1.isEmpty() || normLeague2.isEmpty()) {
            return true;
        }

        // Точное совпадение
        if (normLeague1.equals(normLeague2)) {
            return true;
        }

        // Схожесть > 0.6
        double leagueSimilarity = similarity(normLeague1, normLeague2);
        return leagueSimilarity >= 0.6;
    }

    /**
     * Проверяет, совпадают ли команды
     */
    private boolean isSameTeams(String home1, String away1, String home2, String away2) {
        String normHome1 = normalizeTeamName(home1);
        String normAway1 = normalizeTeamName(away1);
        String normHome2 = normalizeTeamName(home2);
        String normAway2 = normalizeTeamName(away2);

        // Прямое совпадение
        if (normHome1.equals(normHome2) && normAway1.equals(normAway2)) {
            return true;
        }

        // Перестановка (хозяева/гости перепутаны)
        if (normHome1.equals(normAway2) && normAway1.equals(normHome2)) {
            return true;
        }

        // Частичное совпадение (одна команда входит в название другой)
        if ((normHome1.contains(normHome2) || normHome2.contains(normHome1)) &&
                (normAway1.contains(normAway2) || normAway2.contains(normAway1))) {
            return true;
        }

        // Проверка по схожести (порог 0.7)
        double homeSimilarity = Math.max(
                similarity(normHome1, normHome2),
                similarity(normHome1, normAway2)
        );
        double awaySimilarity = Math.max(
                similarity(normAway1, normAway2),
                similarity(normAway1, normHome2)
        );

        return homeSimilarity >= 0.7 && awaySimilarity >= 0.7;
    }

    /**
     * ГЛАВНЫЙ МЕТОД: проверяет, совпадают ли два события (с учётом лиг)
     */
    public boolean isSameEvent(String home1, String away1, String league1,
                               String home2, String away2, String league2) {
        // 1. Проверяем лиги
        if (!isSameLeague(league1, league2)) {
            log.debug("Лиги не совпадают: '{}' vs '{}'", league1, league2);
            return false;
        }

        // 2. Проверяем команды
        boolean teamsMatch = isSameTeams(home1, away1, home2, away2);

        if (teamsMatch) {
            log.debug("События совпали: {} vs {} ({} | {})", home1, away1, league1, league2);
        }

        return teamsMatch;
    }

    /**
     * Перегруженный метод для обратной совместимости (без лиг)
     */
    public boolean isSameEvent(String home1, String away1, String home2, String away2) {
        return isSameTeams(home1, away1, home2, away2);
    }
}