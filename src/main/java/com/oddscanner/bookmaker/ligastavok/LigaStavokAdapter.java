package com.oddscanner.bookmaker.ligastavok;

import com.microsoft.playwright.*;
import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
@Order(4)
public class LigaStavokAdapter implements BookmakerAdapter {

    private static final Logger log = LoggerFactory.getLogger(LigaStavokAdapter.class);
    private static final String BASE_URL = "https://www.ligastavok.ru";
    private static final String CDP_URL = "http://localhost:9222";

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Хранилище для URL, извлечённых из API
    private final Map<String, String> eventUrlCache = new HashMap<>();

    @Override
    public String code() {
        return "ligastavok";
    }

    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== НАЧАЛО ПАРСИНГА ЛИГА СТАВОК ===");
        eventUrlCache.clear();

        List<RawEvent> allEvents = new ArrayList<>();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().connectOverCDP(CDP_URL);
            log.info("✅ Подключено к Chrome");

            // Находим или создаём страницу
            Page page = findOrCreatePrematchPage(browser);
            if (page == null) {
                log.error("❌ Не найдена вкладка с Лигой Ставок");
                return allEvents;
            }

            // НАСТРАИВАЕМ ПЕРЕХВАТ API-ЗАПРОСОВ
            setupApiInterceptor(page);

            // Обновляем страницу
            page.reload();
            page.waitForTimeout(5000); // Ждём загрузки API

            // Даём время на обработку API-ответов
            page.waitForTimeout(3000);

            // Теперь в eventUrlCache должны быть правильные URL

            // Скроллим для подгрузки всех событий
            page.evaluate("window.scrollTo(0, document.body.scrollHeight)");
            page.waitForTimeout(2000);
            page.evaluate("window.scrollTo(0, 0)");
            page.waitForTimeout(1000);

            // Парсим HTML
            String html = page.content();
            saveDebugHtml(html);

            // Парсим события, передавая кэш с URL
            allEvents = parseEventsFromHtml(html, eventUrlCache);

        } catch (Exception e) {
            log.error("❌ Ошибка: {}", e.getMessage(), e);
        }

        log.info("=== ЛИГА СТАВОК: найдено {} событий ===", allEvents.size());
        return allEvents;
    }

    private Page findOrCreatePrematchPage(Browser browser) {
        // Ищем существующую страницу с прематчем
        for (BrowserContext context : browser.contexts()) {
            for (Page page : context.pages()) {
                String url = page.url();
                if (url.contains("ligastavok.ru/prematch/") || url.contains("ligastavok.ru/line/")) {
                    log.info("✅ Найдена прематч вкладка: {}", url);
                    return page;
                }
            }
        }

        // Если не нашли, создаём новую страницу
        Page newPage = browser.newPage();
        newPage.navigate(BASE_URL + "/prematch/soccer");
        newPage.waitForTimeout(3000);
        log.info("🆕 Создана новая страница прематч");
        return newPage;
    }

    /**
     * Настраивает перехват API-ответов для извлечения полных URL событий
     */
    private void setupApiInterceptor(Page page) {
        // Перехватываем ответы от API
        page.onResponse(response -> {
            String url = response.url();

            // Ищем API-запросы с данными о событиях
            if (url.contains("/api/v2/") && url.contains("events")) {
                log.debug("📡 Перехвачен API запрос: {}", url);

                try {
                    // API возвращает JSON
                    String body = response.text();
                    if (body != null && !body.isEmpty()) {
                        parseEventUrlsFromApiJson(body);
                    }
                } catch (Exception e) {
                    log.warn("Ошибка парсинга API ответа: {}", e.getMessage());
                }
            }
        });
    }

    /**
     * Извлекает правильные URL событий из JSON API
     */
    private void parseEventUrlsFromApiJson(String json) {
        try {
            JsonNode root = objectMapper.readTree(json);

            // Ищем массив событий (структура зависит от API)
            JsonNode events = findEventsArray(root);
            if (events == null) return;

            for (JsonNode eventNode : events) {
                // Ищем ID события
                String eventId = extractEventId(eventNode);
                if (eventId == null) continue;

                // Ищем URL события
                String eventUrl = extractEventUrl(eventNode);
                if (eventUrl != null && !eventUrl.isEmpty()) {
                    // Сохраняем в кэш: externalId → полный URL
                    String fullUrl = eventUrl.startsWith("http") ? eventUrl : BASE_URL + eventUrl;
                    eventUrlCache.put(eventId, fullUrl);
                    log.debug("📍 Найден URL для события {}: {}", eventId, fullUrl);
                }
            }

            log.info("📦 Загружено {} URL событий из API", eventUrlCache.size());

        } catch (Exception e) {
            log.warn("Ошибка парсинга API JSON: {}", e.getMessage());
        }
    }

    private JsonNode findEventsArray(JsonNode node) {
        // Рекурсивно ищем массив с ключами "events" или "items"
        if (node.isArray()) {
            // Проверяем, что это массив событий (хотя бы один элемент имеет id)
            if (node.size() > 0 && node.get(0).has("id")) {
                return node;
            }
        }

        if (node.has("events")) return node.get("events");
        if (node.has("items")) return node.get("items");
        if (node.has("data")) return node.get("data");

        // Рекурсивный поиск
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        while (fields.hasNext()) {
            JsonNode child = fields.next().getValue();
            JsonNode result = findEventsArray(child);
            if (result != null) return result;
        }

        return null;
    }

    private String extractEventId(JsonNode eventNode) {
        if (eventNode.has("id")) {
            JsonNode idNode = eventNode.get("id");
            if (idNode.isNumber()) return String.valueOf(idNode.asLong());
            if (idNode.isTextual()) return idNode.asText();
        }
        return null;
    }

    private String extractEventUrl(JsonNode eventNode) {
        // Проверяем разные возможные поля с URL
        if (eventNode.has("url")) return eventNode.get("url").asText();
        if (eventNode.has("link")) return eventNode.get("link").asText();
        if (eventNode.has("href")) return eventNode.get("href").asText();

        // У некоторых букмекеров URL строится из slug
        if (eventNode.has("slug")) {
            String slug = eventNode.get("slug").asText();
            return "/sports/" + slug;
        }

        return null;
    }

    private List<RawEvent> parseEventsFromHtml(String html, Map<String, String> urlCache) {
        List<RawEvent> events = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        // Ищем карточки событий по data-t-event-id
        Pattern cardPattern = Pattern.compile(
                "<article[^>]*data-t-event-id=\"(\\d+)\"[^>]*>(.*?)</article>",
                Pattern.DOTALL
        );

        Matcher cardMatcher = cardPattern.matcher(html);

        while (cardMatcher.find()) {
            String eventIdRaw = cardMatcher.group(1);
            String cardHtml = cardMatcher.group(2);

            String tournament = extractText(cardHtml, "event-card-title__text");
            List<String> teams = extractTeams(cardHtml);
            if (teams.size() < 2) continue;

            String homeTeam = cleanTeamName(teams.get(0));
            String awayTeam = cleanTeamName(teams.get(1));
            String key = homeTeam + "_" + awayTeam;

            if (processed.contains(key)) continue;

            List<BigDecimal> odds = extractOdds(cardHtml);
            if (odds.size() < 3) continue;

            processed.add(key);

            String eventId = "ls_" + eventIdRaw;
            String marketId = eventId + "_1X2";

            // ===== ИСПРАВЛЕННОЕ ФОРМИРОВАНИЕ URL =====
            // Сначала проверяем кэш из API
            String eventUrl = urlCache.get(eventIdRaw);

            if (eventUrl == null) {
                // Если в кэше нет — пробуем альтернативные способы
                // Для футбола пытаемся построить URL из названий команд
                eventUrl = buildUrlFromTeams(homeTeam, awayTeam, eventIdRaw);
                log.warn("⚠️ URL не найден в API для ID {}, сформирован из команд: {}", eventIdRaw, eventUrl);
            } else {
                log.info("🔗 Использован API URL для события {}: {}", eventIdRaw, eventUrl);
            }
            // =======================================

            List<RawOutcome> outcomes = Arrays.asList(
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("HOME_WIN").outcomeValueDescription(homeTeam).odds(odds.get(0)).isActive(true).build(),
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("DRAW").outcomeValueDescription("Ничья").odds(odds.get(1)).isActive(true).build(),
                    RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("AWAY_WIN").outcomeValueDescription(awayTeam).odds(odds.get(2)).isActive(true).build()
            );

            RawMarket market = RawMarket.builder()
                    .externalId(marketId)
                    .externalEventId(eventId)
                    .marketTypeName("WIN_DRAW_WIN")
                    .periodName("FULL_TIME")
                    .outcomes(outcomes)
                    .build();

            RawEvent event = new RawEvent();
            event.setExternalId(eventId);
            event.setHomeTeamName(homeTeam);
            event.setAwayTeamName(awayTeam);
            event.setStartTime(LocalDateTime.now().plusHours(2));
            event.setLeagueName(tournament.isEmpty() ? "Футбол" : tournament);
            event.setSportName("Футбол");
            event.setEventUrl(eventUrl);
            event.setMarkets(List.of(market));

            events.add(event);
            log.info("✅ {} - {} | {} | {} | {} | URL: {}", tournament, homeTeam, awayTeam, odds.get(0), odds.get(1), odds.get(2), eventUrl);
        }

        if (events.isEmpty()) {
            log.info("🔄 Пробуем альтернативный парсинг...");
            events = parseAlternative(html);
        }

        return events;
    }

    /**
     * Формирует правильный URL для события на основе названий команд
     * Формат: /sports/soccer/{slug}-id-{eventId}-service-id-26-ext-id-{extId}
     */
    private String buildUrlFromTeams(String homeTeam, String awayTeam, String eventIdRaw) {
        // Создаём slug из названий команд
        String slug = createSlug(homeTeam) + "-" + createSlug(awayTeam);
        // ext-id можно получить из API, но если его нет — используем хеш
        String extId = String.valueOf(Math.abs((homeTeam + awayTeam).hashCode()) % 1000000);

        return BASE_URL + "/sports/soccer/" + slug + "-id-" + eventIdRaw + "-service-id-26-ext-id-" + extId;
    }

    /**
     * Преобразует название команды в slug
     */
    private String createSlug(String teamName) {
        if (teamName == null) return "";

        String slug = teamName.toLowerCase()
                .replaceAll("[^a-zа-яё\\s-]", "")  // оставляем буквы, пробелы и дефисы
                .trim()
                .replaceAll("\\s+", "-");          // пробелы → дефисы

        // Транслитерация русских букв (упрощённая)
        Map<Character, String> translit = new HashMap<>();
        translit.put('а', "a"); translit.put('б', "b"); translit.put('в', "v");
        translit.put('г', "g"); translit.put('д', "d"); translit.put('е', "e");
        translit.put('ё', "e"); translit.put('ж', "zh"); translit.put('з', "z");
        translit.put('и', "i"); translit.put('й', "y"); translit.put('к', "k");
        translit.put('л', "l"); translit.put('м', "m"); translit.put('н', "n");
        translit.put('о', "o"); translit.put('п', "p"); translit.put('р', "r");
        translit.put('с', "s"); translit.put('т', "t"); translit.put('у', "u");
        translit.put('ф', "f"); translit.put('х', "kh"); translit.put('ц', "ts");
        translit.put('ч', "ch"); translit.put('ш', "sh"); translit.put('щ', "shch");
        translit.put('ъ', ""); translit.put('ы', "y"); translit.put('ь', "");
        translit.put('э', "e"); translit.put('ю', "yu"); translit.put('я', "ya");

        StringBuilder result = new StringBuilder();
        for (char c : slug.toCharArray()) {
            if (translit.containsKey(c)) {
                result.append(translit.get(c));
            } else {
                result.append(c);
            }
        }

        return result.toString();
    }

    private String extractText(String html, String className) {
        Pattern pattern = Pattern.compile("class=\"[^\"]*" + className + "[^\"]*\"[^>]*>([^<]+)</");
        Matcher matcher = pattern.matcher(html);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private List<String> extractTeams(String html) {
        List<String> teams = new ArrayList<>();
        Pattern pattern = Pattern.compile("event-card-teams__team[^\"]*\"[^>]*>([^<]+)</div>");
        Matcher matcher = pattern.matcher(html);
        while (matcher.find() && teams.size() < 2) {
            teams.add(matcher.group(1).trim());
        }
        return teams;
    }

    private List<BigDecimal> extractOdds(String cardHtml) {
        List<BigDecimal> odds = new ArrayList<>();

        // Ищем коэффициенты ТОЛЬКО для рынка WIN_DRAW_WIN
        // Нужно искать секцию с "Основные пары" или "Исход матча"

        // Временно: ограничить диапазон допустимых коэффициентов
        Pattern pattern = Pattern.compile("(\\d+\\.\\d{2})");
        Matcher matcher = pattern.matcher(cardHtml);

        // Ищем коэффициенты, которые находятся РЯДОМ друг с другом
        // и в разумном диапазоне (1.01 - 15.00)
        while (matcher.find() && odds.size() < 3) {
            try {
                BigDecimal odd = new BigDecimal(matcher.group(1));
                // Реалистичные коэффициенты для 1X2
                if (odd.compareTo(new BigDecimal("1.01")) >= 0 &&
                        odd.compareTo(new BigDecimal("15.00")) <= 0) {
                    odds.add(odd);
                }
            } catch (Exception e) {}
        }

        return odds;
    }


    private List<RawEvent> parseAlternative(String html) {
        List<RawEvent> events = new ArrayList<>();
        Pattern pattern = Pattern.compile(
                "([А-Я][а-я]+(?:\\s[А-Я][а-я]+)*)\\s*[-–]\\s*([А-Я][а-я]+(?:\\s[А-Я][а-я]+)*).*?(\\d+\\.\\d{2}).*?(\\d+\\.\\d{2}).*?(\\d+\\.\\d{2})",
                Pattern.DOTALL
        );
        Matcher matcher = pattern.matcher(html);
        Set<String> processed = new HashSet<>();

        while (matcher.find()) {
            String homeTeam = cleanTeamName(matcher.group(1));
            String awayTeam = cleanTeamName(matcher.group(2));
            String key = homeTeam + "_" + awayTeam;

            if (processed.contains(key)) continue;
            if (homeTeam.equals(awayTeam)) continue;
            if (homeTeam.length() < 2 || awayTeam.length() < 2) continue;

            try {
                BigDecimal odd1 = new BigDecimal(matcher.group(3));
                BigDecimal oddX = new BigDecimal(matcher.group(4));
                BigDecimal odd2 = new BigDecimal(matcher.group(5));

                processed.add(key);

                String eventId = "ls_alt_" + System.currentTimeMillis() + "_" + Math.abs(key.hashCode());
                String marketId = eventId + "_1X2";

                // Для альтернативного парсинга пытаемся построить URL
                String eventUrl = buildUrlFromTeams(homeTeam, awayTeam, String.valueOf(Math.abs(key.hashCode())));

                List<RawOutcome> outcomes = Arrays.asList(
                        RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("HOME_WIN").odds(odd1).isActive(true).build(),
                        RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("DRAW").odds(oddX).isActive(true).build(),
                        RawOutcome.builder().externalMarketId(marketId).outcomeKeyName("AWAY_WIN").odds(odd2).isActive(true).build()
                );

                RawMarket market = RawMarket.builder()
                        .externalId(marketId)
                        .externalEventId(eventId)
                        .marketTypeName("WIN_DRAW_WIN")
                        .periodName("FULL_TIME")
                        .outcomes(outcomes)
                        .build();

                RawEvent event = new RawEvent();
                event.setExternalId(eventId);
                event.setHomeTeamName(homeTeam);
                event.setAwayTeamName(awayTeam);
                event.setStartTime(LocalDateTime.now().plusHours(2));
                event.setLeagueName("Футбол");
                event.setSportName("Футбол");
                event.setEventUrl(eventUrl);
                event.setMarkets(List.of(market));

                events.add(event);
                log.info("✅(alt) {} - {} | {} | {} | {} | URL: {}", homeTeam, awayTeam, odd1, oddX, odd2, eventUrl);

            } catch (Exception e) {}
        }

        return events;
    }

    private void saveDebugHtml(String html) {
        try {
            java.nio.file.Files.writeString(java.nio.file.Paths.get("ligastavok_debug.html"), html);
            log.info("💾 HTML сохранён в ligastavok_debug.html");
        } catch (Exception e) {}
    }

    private String cleanTeamName(String name) {
        if (name == null) return "";
        return name.trim()
                .replaceAll("\\s+", " ")
                .replaceAll("[^\\p{L}\\p{N}\\s]", "");
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        return new ArrayList<>();
    }
}