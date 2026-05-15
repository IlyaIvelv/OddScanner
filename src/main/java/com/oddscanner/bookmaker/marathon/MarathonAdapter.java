package com.oddscanner.bookmaker.marathon;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.oddscanner.generated.Tables;
import com.oddscanner.generated.tables.records.EventsRecord;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import com.oddscanner.generated.tables.records.LeaguesRecord;
import com.oddscanner.generated.tables.records.TeamsRecord;
import org.jooq.DSLContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarathonAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);
    private static final String BASE_URL = "https://www.marathonbet.ru";
    private static final Pattern EVENT_ID_PATTERN = Pattern.compile("/event/(\\d+)");

    private final DSLContext dsl;

    public MarathonAdapter(DSLContext dsl) {
        this.dsl = dsl;
    }

    @Override
    public String code() {
        return "marathon";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА MARATHON ===");

        // Список лиг для парсинга (только футбол)
        List<String> leagueUrls = Arrays.asList(
                "/su/betting/Football/Spain/Primera+Division+-+8736?interval=ALL_TIME",
                "/su/betting/Football/England/Premier+League+-+21520?interval=ALL_TIME",
                "/su/betting/Football/Russia/Premier+League+-+22433?interval=ALL_TIME"
        );

        int totalSaved = 0;

        for (String leagueUrl : leagueUrls) {
            try {
                log.info("Парсинг лиги: {}", leagueUrl);
                int saved = parseAndSaveLeague(leagueUrl);
                totalSaved += saved;
                log.info("Сохранено {} событий из лиги", saved);
                Thread.sleep(2000);
            } catch (Exception e) {
                log.error("Ошибка парсинга лиги {}: {}", leagueUrl, e.getMessage());
            }
        }

        log.info("=== MARATHON: Всего сохранено {} событий ===", totalSaved);
        return new ArrayList<>();
    }


    private int parseAndSaveLeague(String leagueUrl) {
        int savedCount = 0;

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(false)
                    .setSlowMo(300)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Page page = browser.newPage();
            page.navigate(BASE_URL + leagueUrl);
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(5000);

            log.info("=== СТРАНИЦА ЗАГРУЖЕНА ===");

            // Ищем элементы с data-event-id
            List<ElementHandle> eventElements = page.querySelectorAll("[data-event-id]");
            log.info("Найдено [data-event-id]: {}", eventElements.size());

            if (eventElements.isEmpty()) {
                // Ищем элементы с data-id
                eventElements = page.querySelectorAll("[data-id]");
                log.info("Найдено [data-id]: {}", eventElements.size());
            }

            if (eventElements.isEmpty()) {
                // Ищем элементы с классом event
                eventElements = page.querySelectorAll("[class*='event']");
                log.info("Найдено [class*='event']: {}", eventElements.size());
            }

            if (eventElements.isEmpty()) {
                log.warn("НЕ НАЙДЕНО ЭЛЕМЕНТОВ СОБЫТИЙ!");
                browser.close();
                return 0;
            }

            for (ElementHandle element : eventElements) {
                try {
                    String externalId = element.getAttribute("data-event-id");
                    if (externalId == null) {
                        externalId = element.getAttribute("data-id");
                    }
                    if (externalId == null) {
                        externalId = "unknown";
                    }

                    String text = element.textContent().trim();
                    log.info("Текст элемента: {}", text.substring(0, Math.min(200, text.length())));

                    String homeTeam = "Unknown";
                    String awayTeam = "TBD";

                    // Ищем .member-name внутри элемента
                    List<ElementHandle> members = element.querySelectorAll(".member-name");
                    if (members.size() >= 2) {
                        homeTeam = members.get(0).textContent().trim();
                        awayTeam = members.get(1).textContent().trim();
                    }

                    // Если не нашли, пробуем через сплит
                    if ("Unknown".equals(homeTeam) && text.contains(" - ")) {
                        String[] parts = text.split(" - ");
                        if (parts.length >= 2) {
                            homeTeam = parts[0].trim();
                            awayTeam = parts[1].trim();
                        }
                    }

                    if ("Unknown".equals(homeTeam)) {
                        // Пробуем найти команды через регулярное выражение
                        String[] words = text.split("\\s+");
                        for (int i = 0; i < words.length - 1; i++) {
                            if (words[i].length() > 2 && words[i].matches("[А-Яа-яA-Za-z]+")) {
                                homeTeam = words[i];
                                for (int j = i + 1; j < words.length; j++) {
                                    if (words[j].length() > 2 && words[j].matches("[А-Яа-яA-Za-z]+")) {
                                        awayTeam = words[j];
                                        break;
                                    }
                                }
                                break;
                            }
                        }
                    }

                    log.info("Команды: {} vs {}, ID: {}", homeTeam, awayTeam, externalId);

                    if ("Unknown".equals(homeTeam) || "TBD".equals(awayTeam)) {
                        log.warn("Не удалось определить команды");
                        continue;
                    }

                    Long homeTeamId = findOrCreateTeam(homeTeam);
                    Long awayTeamId = findOrCreateTeam(awayTeam);

                    EventsRecord existing = dsl.selectFrom(Tables.EVENTS)
                            .where(Tables.EVENTS.HOME_TEAM_ID.eq(homeTeamId))
                            .and(Tables.EVENTS.AWAY_TEAM_ID.eq(awayTeamId))
                            .fetchOne();

                    if (existing != null) {
                        continue;
                    }

                    EventsRecord record = dsl.newRecord(Tables.EVENTS);
                    record.setHomeTeamId(homeTeamId);
                    record.setAwayTeamId(awayTeamId);
                    record.setStartTime(LocalDateTime.now().plusHours(2).atOffset(ZoneOffset.UTC));
                    record.setStatus("SCHEDULED");
                    record.store();

                    savedCount++;
                    log.info("✅ СОХРАНЕНО! {} vs {}", homeTeam, awayTeam);

                } catch (Exception e) {
                    log.error("Ошибка: {}", e.getMessage());
                }
            }

            Thread.sleep(5000);
            browser.close();
        } catch (Exception e) {
            log.error("Ошибка парсинга: {}", e.getMessage(), e);
        }

        log.info("=== СОХРАНЕНО {} СОБЫТИЙ ===", savedCount);
        return savedCount;
    }


    private Long findOrCreateTeam(String teamName) {
        if (teamName == null || teamName.isEmpty() || "Unknown".equals(teamName) || "TBD".equals(teamName)) {
            return 1L; // ID команды "Unknown"
        }

        // Ищем существующую команду
        TeamsRecord existing = dsl.selectFrom(Tables.TEAMS)
                .where(Tables.TEAMS.CANONICAL_NAME.eq(teamName))
                .fetchOne();

        if (existing != null) {
            return existing.getId();
        }

        // Создаём новую команду
        TeamsRecord newTeam = dsl.newRecord(Tables.TEAMS);
        newTeam.setCanonicalName(teamName);
        newTeam.setNormalizedName(teamName.toLowerCase());
        newTeam.setSportId(1L); // Football
        newTeam.store();

        log.debug("Создана новая команда: {} (ID: {})", teamName, newTeam.getId());
        return newTeam.getId();
    }

    private Long findOrCreateLeague(String leagueUrl) {
        // Упрощённо: извлекаем название лиги из URL
        String leagueName = leagueUrl.split("/")[3]; // Пример: "Spain"

        LeaguesRecord existing = dsl.selectFrom(Tables.LEAGUES)
                .where(Tables.LEAGUES.NAME.eq(leagueName))
                .fetchOne();

        if (existing != null) {
            return existing.getId();
        }

        LeaguesRecord newLeague = dsl.newRecord(Tables.LEAGUES);
        newLeague.setName(leagueName);
        newLeague.setSportId(1L);
        newLeague.store();

        return newLeague.getId();
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}