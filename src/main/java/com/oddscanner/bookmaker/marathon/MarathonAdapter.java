package com.oddscanner.bookmaker.marathon;

import com.oddscanner.bookmaker.api.BookmakerAdapter;
import com.oddscanner.bookmaker.api.RawEvent;
import com.oddscanner.bookmaker.api.RawMarket;
import com.oddscanner.bookmaker.api.RawOutcome;
import com.microsoft.playwright.*;
import com.microsoft.playwright.options.LoadState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MarathonAdapter implements BookmakerAdapter {
    private static final Logger log = LoggerFactory.getLogger(MarathonAdapter.class);
    private static final String BASE_URL = "https://www.marathonbet.ru";
    private static final String FOOTBALL_URL = "/su/betting/Football/";

    private final Map<String, List<RawMarket>> marketsCache = new HashMap<>();

    public MarathonAdapter() {}

    @Override
    public String code() {
        return "marathon";
    }


    @Override
    public List<RawEvent> fetchEvents() {
        log.info("=== MARATHON fetchEvents() START ===");
        log.info("=== НАЧАЛО ПАРСИНГА MARATHON ===");

        List<RawEvent> allEvents = new ArrayList<>();
        marketsCache.clear();

        Playwright playwright = null;
        Browser browser = null;
        Page page = null;

        try {
            log.info("Создаем экземпляр Playwright...");
            playwright = Playwright.create();
            log.info("Playwright создан успешно");

            log.info("Запускаем браузер Chromium...");
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)
                    .setSlowMo(100)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));
            log.info("Браузер запущен успешно");

            log.info("Создаем новую страницу...");
            page = browser.newPage();
            log.info("Страница создана");

            String fullUrl = BASE_URL + FOOTBALL_URL;
            log.info("Загружаем страницу: {}", fullUrl);
            page.navigate(fullUrl);
            log.info("Страница загружена, ожидаем загрузки контента...");

            // Ждем полной загрузки страницы (включая динамический контент)
            log.info("Ожидаем NetworkIdle...");
            page.waitForLoadState(LoadState.NETWORKIDLE);
            log.info("NetworkIdle достигнут");

            log.info("Ожидаем 10 секунд для React компонентов...");
            page.waitForTimeout(10000);
            log.info("Ожидание завершено");

            // Проверяем наличие селекторов
            log.info("Проверяем наличие селектора #events_content...");
            try {
                page.waitForSelector("#events_content", new Page.WaitForSelectorOptions().setTimeout(30000));
                log.info("Селектор #events_content найден");
            } catch (Exception e) {
                log.warn("Селектор #events_content НЕ НАЙДЕН: {}", e.getMessage());
                // Продолжаем выполнение, возможно селектор другой
            }

            log.info("Проверяем наличие селектора .bg.coupon-row...");
            try {
                page.waitForSelector(".bg.coupon-row", new Page.WaitForSelectorOptions().setTimeout(30000));
                log.info("Селектор .bg.coupon-row найден");
            } catch (Exception e) {
                log.warn("Селектор .bg.coupon-row НЕ НАЙДЕН: {}", e.getMessage());
            }

            // Логируем HTML для отладки (первые 2000 символов)
            String pageContent = page.content();
            log.info("Содержимое страницы (первые 2000 символов): {}",
                    pageContent.length() > 2000 ? pageContent.substring(0, 2000) : pageContent);

            // Находим ВСЕ блоки событий на странице
            log.info("Ищем элементы .bg.coupon-row...");
            List<ElementHandle> eventRows = page.querySelectorAll(".bg.coupon-row");
            log.info("Найдено элементов .bg.coupon-row: {}", eventRows.size());

            if (eventRows.isEmpty()) {
                log.warn("НЕ НАЙДЕНО НИ ОДНОГО СОБЫТИЯ! Возможно изменилась структура сайта.");
                log.info("Доступные классы на странице: {}", getAvailableClasses(page));
            }

            int processedCount = 0;
            int noTeamsCount = 0;
            int errorCount = 0;

            for (int i = 0; i < eventRows.size(); i++) {
                ElementHandle eventRow = eventRows.get(i);
                log.debug("Обработка события {}", i + 1);

                try {
                    // Получаем ID события
                    String eventId = eventRow.getAttribute("data-event-eventid");
                    if (eventId == null) {
                        eventId = eventRow.getAttribute("data-event-treeid");
                        log.debug("data-event-eventid не найден, пробуем data-event-treeid: {}", eventId);
                    }
                    if (eventId == null) {
                        eventId = UUID.randomUUID().toString();
                        log.debug("ID события не найден, генерируем UUID: {}", eventId);
                    }

                    // Парсим команды из .member-name
                    List<ElementHandle> memberNames = eventRow.querySelectorAll(".member-name");
                    if (memberNames.size() < 2) {
                        noTeamsCount++;
                        if (noTeamsCount <= 10) {
                            String eventText = eventRow.textContent();
                            String preview = eventText.length() > 300 ? eventText.substring(0, 300) : eventText;
                            log.warn("Не найдены команды для события #{}, текст: {}", i + 1, preview);
                        }
                        continue;
                    }

                    String homeTeam = memberNames.get(0).textContent().trim();
                    String awayTeam = memberNames.get(1).textContent().trim();
                    log.info("🎯 Найден матч #{}: {} vs {} (ID: {})", i + 1, homeTeam, awayTeam, eventId);

                    // Парсим рынки
                    log.debug("Парсим рынки для события {}", eventId);
                    List<RawMarket> markets = parseMarketsFromEventRow(eventRow, eventId);
                    log.debug("Найдено рынков: {}", markets.size());

                    marketsCache.put(eventId, markets);

                    // Формируем URL события из data-event-path
                    String eventPath = eventRow.getAttribute("data-event-path");
                    String eventUrl;
                    if (eventPath != null && !eventPath.isEmpty()) {
                        eventUrl = BASE_URL + eventPath;
                    } else {
                        eventUrl = BASE_URL + "/su/betting/Football/event/" + eventId;
                    }

                    RawEvent rawEvent = new RawEvent();
                    rawEvent.setExternalId(eventId);
                    rawEvent.setHomeTeamName(homeTeam);
                    rawEvent.setAwayTeamName(awayTeam);
                    rawEvent.setStartTime(LocalDateTime.now().plusDays(7));
                    rawEvent.setLeagueName(extractLeagueName(eventRow));
                    rawEvent.setSportName("Football");
                    rawEvent.setEventUrl(eventUrl);
                    rawEvent.setMarkets(markets);

                    allEvents.add(rawEvent);
                    processedCount++;
                    log.info("✅ Добавлено событие #{}: {} vs {} с {} рынками",
                            processedCount, homeTeam, awayTeam, markets.size());

                } catch (Exception e) {
                    errorCount++;
                    log.error("Ошибка парсинга события #{}: {}", i + 1, e.getMessage(), e);
                }
            }

            log.info("=== ИТОГИ ПАРСИНГА MARATHON ===");
            log.info("Обработано событий: {}", processedCount);
            log.info("Пропущено без команд: {}", noTeamsCount);
            log.info("Ошибок при парсинге: {}", errorCount);
            log.info("Всего собрано RawEvent: {}", allEvents.size());

            if (allEvents.isEmpty()) {
                log.warn("MARATHON НЕ НАШЕЛ НИ ОДНОГО СОБЫТИЯ!");
                log.warn("Проверьте: 1) Доступность сайта {} 2) Структуру страницы", BASE_URL);
            }

        } catch (Exception e) {
            log.error("!!! КРИТИЧЕСКАЯ ОШИБКА В MARATHON fetchEvents() !!!", e);
            log.error("Тип ошибки: {}", e.getClass().getName());
            log.error("Сообщение: {}", e.getMessage());

            // Логируем stack trace полностью
            for (StackTraceElement element : e.getStackTrace()) {
                log.error("  at {}", element);
            }

            return Collections.emptyList();

        } finally {
            // Закрываем ресурсы в правильном порядке
            log.info("Закрываем ресурсы Playwright...");
            try {
                if (page != null) {
                    page.close();
                    log.debug("Page закрыт");
                }
            } catch (Exception e) {
                log.warn("Ошибка при закрытии page: {}", e.getMessage());
            }

            try {
                if (browser != null) {
                    browser.close();
                    log.debug("Browser закрыт");
                }
            } catch (Exception e) {
                log.warn("Ошибка при закрытии browser: {}", e.getMessage());
            }

            try {
                if (playwright != null) {
                    playwright.close();
                    log.debug("Playwright закрыт");
                }
            } catch (Exception e) {
                log.warn("Ошибка при закрытии playwright: {}", e.getMessage());
            }

            log.info("=== MARATHON fetchEvents() FINISHED, returning {} events ===", allEvents.size());
        }

        log.info("=== MARATHON: Найдено {} событий ===", allEvents.size());
        for (RawEvent e : allEvents) {
            log.info("  Marathon event: {} vs {}", e.getHomeTeamName(), e.getAwayTeamName());
        }

        return allEvents;
    }

    // Добавьте этот вспомогательный метод для отладки
    private String getAvailableClasses(Page page) {
        try {
            return page.evaluate("() => {" +
                    "const elements = document.querySelectorAll('[class]');" +
                    "const classes = new Set();" +
                    "elements.forEach(el => {" +
                    "  el.className.split(' ').forEach(c => classes.add(c));" +
                    "});" +
                    "return Array.from(classes).slice(0, 50).join(', ');" +
                    "}").toString();
        } catch (Exception e) {
            return "Unable to get classes: " + e.getMessage();
        }
    }

    /**
     * Извлекает название лиги из контекста события
     */
    /**
     * Извлекает название лиги из контекста события
     */
    private String extractLeagueName(ElementHandle eventRow) {
        try {
            // Получаем родительский элемент
            ElementHandle parent = eventRow;

            // Поднимаемся вверх по дереву, ищем category-container
            for (int i = 0; i < 10; i++) { // максимум 10 уровней вверх
                ElementHandle tmp = parent.querySelector("xpath=..");
                if (tmp == null) break;
                parent = tmp;

                // Проверяем класс родителя
                String className = parent.getAttribute("class");
                if (className != null && className.contains("category-container")) {
                    // Нашли контейнер категории - ищем в нем название
                    ElementHandle categoryLabel = parent.querySelector(".category-label");
                    if (categoryLabel != null) {
                        String leagueText = categoryLabel.textContent().trim();
                        if (leagueText.length() > 0 && leagueText.length() < 100) {
                            return leagueText;
                        }
                    }
                    break;
                }
            }

            // Альтернативный способ: ищем по ближайшему category-container через querySelector
            ElementHandle container = eventRow.querySelector("xpath=ancestor::div[contains(@class, 'category-container')]");
            if (container != null) {
                ElementHandle categoryLabel = container.querySelector(".category-label");
                if (categoryLabel != null) {
                    String leagueText = categoryLabel.textContent().trim();
                    if (leagueText.length() > 0 && leagueText.length() < 100) {
                        return leagueText;
                    }
                }
            }

        } catch (Exception e) {
            log.debug("Не удалось определить лигу: {}", e.getMessage());
        }
        return "Unknown League";
    }

    private List<RawMarket> parseMarketsFromEventRow(ElementHandle eventRow, String eventId) {
        List<RawMarket> markets = new ArrayList<>();

        // Находим все ячейки с ценами (коэффициентами)
        List<ElementHandle> priceCells = eventRow.querySelectorAll("td.price");

        for (ElementHandle cell : priceCells) {
            try {
                String marketType = cell.getAttribute("data-market-type");
                if (marketType == null) continue;

                // Находим span с коэффициентом
                ElementHandle span = cell.querySelector("span.selection-link");
                if (span == null) continue;

                String priceText = span.textContent().trim();
                BigDecimal odds = parseOdds(priceText);
                if (odds == null) continue;

                // Получаем значение линии (фора/тотал) из текста ячейки
                String cellText = cell.textContent().trim();
                String lineValue = extractLineValue(cellText);

                // Определяем исход
                String outcomeKey = determineOutcomeKey(cell, marketType);
                String outcomeDesc = determineOutcomeDescription(cell, marketType, lineValue);
                String mappedMarketType = mapMarketType(marketType);

                // Находим или создаем рынок
                RawMarket market = findOrCreateMarket(markets, eventId, mappedMarketType, lineValue);

                RawOutcome outcome = new RawOutcome();
                outcome.setExternalMarketId(market.getExternalId());
                outcome.setOutcomeKeyName(outcomeKey);
                outcome.setOutcomeValueDescription(outcomeDesc);
                outcome.setOdds(odds);
                outcome.setActive(true);

                if (market.getOutcomes() == null) {
                    market.setOutcomes(new ArrayList<>());
                }
                market.getOutcomes().add(outcome);

                log.debug("Рынок: {}, исход: {}, коэф: {}", marketType, outcomeDesc, odds);

            } catch (Exception e) {
                log.debug("Ошибка парсинга ячейки: {}", e.getMessage());
            }
        }

        return markets;
    }

    private RawMarket findOrCreateMarket(List<RawMarket> markets, String eventId, String marketType, String lineValue) {
        String marketId = eventId + "_" + marketType + (lineValue != null ? "_" + lineValue : "");

        for (RawMarket m : markets) {
            if (m.getExternalId().equals(marketId)) {
                return m;
            }
        }

        RawMarket newMarket = new RawMarket();
        newMarket.setExternalId(marketId);
        newMarket.setExternalEventId(eventId);
        newMarket.setMarketTypeName(marketType);
        newMarket.setPeriodName("FULL_TIME");

        if (lineValue != null && !lineValue.isEmpty()) {
            try {
                newMarket.setLine(new BigDecimal(lineValue));
            } catch (Exception e) {}
        }

        markets.add(newMarket);
        return newMarket;
    }

    private String mapMarketType(String type) {
        switch (type) {
            case "RESULT": return "WIN_DRAW_WIN";
            case "DOUBLE_CHANCE": return "DOUBLE_CHANCE";
            case "HANDICAP": return "HANDICAP";
            case "TOTAL": return "TOTAL_GOALS";
            default: return type;
        }
    }

    private String determineOutcomeKey(ElementHandle cell, String marketType) {
        String cellHtml = cell.innerHTML().toLowerCase();

        switch (marketType) {
            case "RESULT":
                if (cellHtml.contains("match_result.1")) return "HOME_WIN";
                if (cellHtml.contains("match_result.draw")) return "DRAW";
                if (cellHtml.contains("match_result.3")) return "AWAY_WIN";
                break;
            case "DOUBLE_CHANCE":
                if (cellHtml.contains("result.hd")) return "HOME_OR_DRAW";
                if (cellHtml.contains("result.ha")) return "HOME_OR_AWAY";
                if (cellHtml.contains("result.ad")) return "DRAW_OR_AWAY";
                break;
            case "HANDICAP":
                if (cellHtml.contains("hb_h")) return "HOME_HANDICAP";
                if (cellHtml.contains("hb_a")) return "AWAY_HANDICAP";
                break;
            case "TOTAL":
                if (cellHtml.contains("under")) return "UNDER";
                if (cellHtml.contains("over")) return "OVER";
                break;
        }
        return "UNKNOWN";
    }

    private String determineOutcomeDescription(ElementHandle cell, String marketType, String lineValue) {
        String cellHtml = cell.innerHTML().toLowerCase();

        switch (marketType) {
            case "RESULT":
                if (cellHtml.contains("match_result.1")) return "П1";
                if (cellHtml.contains("match_result.draw")) return "X";
                if (cellHtml.contains("match_result.3")) return "П2";
                break;
            case "DOUBLE_CHANCE":
                if (cellHtml.contains("result.hd")) return "1X";
                if (cellHtml.contains("result.ha")) return "12";
                if (cellHtml.contains("result.ad")) return "X2";
                break;
            case "HANDICAP":
                if (cellHtml.contains("hb_h") && lineValue != null) return "Фора " + lineValue + " (хозяева)";
                if (cellHtml.contains("hb_a") && lineValue != null) {
                    try {
                        BigDecimal val = new BigDecimal(lineValue);
                        BigDecimal awayVal = val.negate();
                        return "Фора " + awayVal + " (гости)";
                    } catch (Exception e) {
                        return "Фора (гости)";
                    }
                }
                break;
            case "TOTAL":
                if (cellHtml.contains("under") && lineValue != null) return "Меньше " + lineValue;
                if (cellHtml.contains("over") && lineValue != null) return "Больше " + lineValue;
                break;
        }
        return cell.textContent().trim();
    }

    private String extractLineValue(String cellText) {
        Pattern pattern = Pattern.compile("\\(([-+]?\\d+\\.?\\d*)\\)");
        Matcher matcher = pattern.matcher(cellText);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    private BigDecimal parseOdds(String priceText) {
        try {
            String cleaned = priceText.replace(",", ".");
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public List<RawMarket> fetchMarkets(String externalEventId) {
        if (marketsCache.containsKey(externalEventId)) {
            log.debug("Возвращаем {} рынков для события {} из кэша",
                    marketsCache.get(externalEventId).size(), externalEventId);
            return marketsCache.get(externalEventId);
        }
        return new ArrayList<>();
    }
}