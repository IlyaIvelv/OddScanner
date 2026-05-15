// File: src/main/java/com/oddscanner/bookmaker/api/RawEvent.java

package com.oddscanner.bookmaker.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

/**
 * DTO для представления "сырого" события от букмекера (например, футбольный матч).
 * Содержит информацию до нормализации.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawEvent {
    private String externalId; // Уникальный ID события у букмекера (например, "123456789")
    private String homeTeamName; // Имя домашней команды как есть
    private String awayTeamName; // Имя гостевой команды как есть
    private LocalDateTime startTime; // Время начала события
    private String leagueName; // Название лиги как есть
    private String sportName;
    private String eventUrl; // Название вида спорта как есть
    private List<RawMarket> markets; // Список рынков для этого события (может быть null или пустым при инициализации)
    // Дополнительные поля, если необходимы (статус, счет и т.д.)
}