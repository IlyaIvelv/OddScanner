
package com.oddscanner.bookmaker.api;

import java.util.List;

/**
 * Интерфейс адаптера для конкретного букмекера.
 * Определяет контракт для получения данных о событиях и рынках.
 */
public interface BookmakerAdapter {

    /**
     * Получает уникальный код букмекера (например, "fonbet", "marathon").
     *
     * @return код букмекера.
     */
    String code();

    /**
     * Загружает список событий (например, матчей) с сайта/через API букмекера.
     *
     * @return список объектов {@link RawEvent}.
     */
    List<RawEvent> fetchEvents();

    /**
     * Загружает список рынков (ставок) для конкретного события.
     *
     * @param externalEventId Внешний ID события у букмекера.
     * @return список объектов {@link RawMarket}.
     */
    List<RawMarket> fetchMarkets(String externalEventId);
}