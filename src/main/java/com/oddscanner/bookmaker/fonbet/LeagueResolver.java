package com.oddscanner.bookmaker.fonbet;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.HashSet;

@Component
@Slf4j
public class LeagueResolver {

    // Кэш: ключ - "team1|team2", значение - название лиги
    private final Map<String, String> leagueCache = new ConcurrentHashMap<>();

    // Словарь правил: ключ - regex паттерн для пары команд, значение - лига
    private final Map<Pattern, String> leagueRules = new ConcurrentHashMap<>();

    public LeagueResolver() {
        // Инициализируем правила для самых частых матчей
        initDefaultRules();
    }

    private void initDefaultRules() {
        // Футбол - Россия
        addRule(".*Зенит.*Спартак.*", "Российская Премьер-Лига");
        addRule(".*ЦСКА.*Локомотив.*Москва.*", "Российская Премьер-Лига");
        addRule(".*Краснодар.*Ростов.*", "Российская Премьер-Лига");

        // Футбол - Англия
        addRule(".*Челси.*Манчестер Сити.*", "Английская Премьер-Лига");
        addRule(".*Арсенал.*Тоттенхэм.*", "Английская Премьер-Лига");
        addRule(".*Манчестер Юнайтед.*Ливерпуль.*", "Английская Премьер-Лига");

        // Футбол - Испания
        addRule(".*Барселона.*Реал Мадрид.*", "La Liga");
        addRule(".*Атлетико Мадрид.*Севилья.*", "La Liga");

        // Футбол - Германия
        addRule(".*Бавария.*Боруссия Дортмунд.*", "Бундеслига");

        // Хоккей - КХЛ
        addRule(".*Новосибирск.*Металлург.*", "КХЛ");
        addRule(".*ЦСКА.*СКА.*", "КХЛ");
        addRule(".*Ак Барс.*Локомотив Ярославль.*", "КХЛ");

        // Баскетбол - НБА
        addRule(".*Вашингтон.*Миннесота.*", "NBA");
        addRule(".*Милуоки.*Чикаго.*", "NBA");
        addRule(".*Торонто.*Портленд.*", "NBA");
        addRule(".*Хьюстон.*Сан-Антонио.*", "NBA");

        // Киберспорт - CS2
        addRule(".*MOUZ.*Team Spirit.*", "CS2 - Турнир");
        addRule(".*Team Falcons.*GamerLegion.*", "CS2 - Турнир");
        addRule(".*Virtus.Pro.*Natus Vincere.*", "CS2 - Турнир");

        // Можно добавлять сколько угодно правил
    }

    private void addRule(String regex, String leagueName) {
        leagueRules.put(Pattern.compile(regex, Pattern.CASE_INSENSITIVE), leagueName);
    }

    /**
     * Основной метод определения лиги по названиям команд
     */
    public String resolveLeague(String team1, String team2) {
        if (team1 == null || team2 == null) {
            return "Unknown League";
        }

        String cacheKey = team1 + "|" + team2;

        // 1. Проверяем кэш
        if (leagueCache.containsKey(cacheKey)) {
            return leagueCache.get(cacheKey);
        }

        String combined = team1 + " vs " + team2;

        // 2. Проверяем по правилам
        for (Map.Entry<Pattern, String> entry : leagueRules.entrySet()) {
            if (entry.getKey().matcher(combined).matches()) {
                leagueCache.put(cacheKey, entry.getValue());
                log.debug("Определена лига '{}' для матча {}", entry.getValue(), combined);
                return entry.getValue();
            }
        }

        // 3. Если не нашли - возвращаем Unknown
        log.warn("Не удалось определить лигу для матча {}", combined);
        leagueCache.put(cacheKey, "Unknown League");
        return "Unknown League";
    }

    /**
     * Метод для динамического добавления новых правил (можно вызвать через админку)
     */
    public void addRuleDynamically(String team1Pattern, String team2Pattern, String leagueName) {
        String regex = ".*" + Pattern.quote(team1Pattern) + ".*" + Pattern.quote(team2Pattern) + ".*";
        addRule(regex, leagueName);
        log.info("Добавлено новое правило: {} vs {} -> {}", team1Pattern, team2Pattern, leagueName);
    }
}