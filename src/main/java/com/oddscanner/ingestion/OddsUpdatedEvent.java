package com.oddscanner.ingestion;

import java.time.LocalDateTime;

/**
 * Событие, сигнализирующее об обновлении коэффициентов от букмекера.
 * Публикуется IngestionService после сохранения новых данных.
 */
public class OddsUpdatedEvent {

    private final String bookmakerCode;
    private final String eventId; // Внешний ID события у букмекера
    private final LocalDateTime timestamp;

    public OddsUpdatedEvent(String bookmakerCode, String eventId) {
        this.bookmakerCode = bookmakerCode;
        this.eventId = eventId;
        this.timestamp = LocalDateTime.now(); // Время публикации события
    }

    public String getBookmakerCode() {
        return bookmakerCode;
    }

    public String getEventId() {
        return eventId;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}