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
        log.info("=== НАЧАЛО ПАРСИНГА MARATHON ===");

        List<RawEvent> allEvents = new ArrayList<>();
        marketsCache.clear();

        try (Playwright playwright = Playwright.create()) {
            Browser browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
                    .setHeadless(true)  // Можно поставить false для отладки
                    .setSlowMo(100)
                    .setArgs(List.of("--no-sandbox", "--disable-dev-shm-usage")));

            Page page = browser.newPage();

            // Заходим на страницу футбола
            page.navigate(BASE_URL + FOOTBALL_URL);
            log.info("Загружена страница футбола");

            // Ждем полной загрузки страницы (включая динамический контент)
            page.waitForLoadState(LoadState.NETWORKIDLE);
            page.waitForTimeout(10000); // Даем время на загрузку React компонентов

            // Ждем появления контейнера с событиями
            page.waitForSelector("#events_content", new Page.WaitForSelectorOptions().setTimeout(30000));
            page.waitForSelector(".bg.coupon-row", new Page.WaitForSelectorOptions().setTimeout(30000));

            // Находим ВСЕ блоки событий на странице
            List<ElementHandle> eventRows = page.querySelectorAll(".bg.coupon-row");
            log.info("Найдено событий на странице: {}", eventRows.size());

            int processedCount = 0;
            int noTeamsCount = 0;

            for (ElementHandle eventRow : eventRows) {
                try {
                    // Получаем ID события
                    String eventId = eventRow.getAttribute("data-event-eventid");
                    if (eventId == null) {
                        eventId = eventRow.getAttribute("data-event-treeid");
                    }
                    if (eventId == null) {
                        eventId = UUID.randomUUID().toString();
                    }

                    // Парсим команды из .member-name
                    List<ElementHandle> memberNames = eventRow.querySelectorAll(".member-name");
                    if (memberNames.size() < 2) {
                        noTeamsCount++;
                        if (noTeamsCount <= 5) {
                            log.debug("Не найдены команды для события, текст: {}",
                                    eventRow.textContent().substring(0, Math.min(200, eventRow.textContent().length())));
                        }
                        continue;
                    }

                    String homeTeam = memberNames.get(0).textContent().trim();
                    String awayTeam = memberNames.get(1).textContent().trim();

                    log.info("🎯 Найден матч: {} vs {} (ID: {})", homeTeam, awayTeam, eventId);

                    // Парсим рынки
                    List<RawMarket> markets = parseMarketsFromEventRow(eventRow, eventId);
                    marketsCache.put(eventId, markets);

                    // Формируем URL события из data-event-path
                    String eventPath = eventRow.getAttribute("data-event-path");
                    String eventUrl;
                    if (eventPath != null && !eventPath.isEmpty()) {
                        eventUrl = BASE_URL + eventPath;
                    } else {
                        // Fallback если path нет
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
                    log.info("✅ Добавлено событие {} vs {} с {} рынками", homeTeam, awayTeam, markets.size());

                } catch (Exception e) {
                    log.error("Ошибка парсинга события: {}", e.getMessage());
                }
            }

            log.info("Обработано {} событий, пропущено без команд: {}", processedCount, noTeamsCount);

            page.close();
            browser.close();

        } catch (Exception e) {
            log.error("Ошибка инициализации браузера: {}", e.getMessage(), e);
        }

        log.info("=== MARATHON: Всего собрано {} событий ===", allEvents.size());
        return allEvents;
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