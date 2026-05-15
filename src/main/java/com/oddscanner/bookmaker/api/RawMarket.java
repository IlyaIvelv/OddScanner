// File: src/main/java/com/oddscanner/bookmaker/api/RawMarket.java

package com.oddscanner.bookmaker.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/**
 * DTO для представления "сырого" рынка (типа ставки) от букмекера.
 * Например, "Тотал голов", "Фора", "Индивидуальный тотал".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawMarket {
    private String externalId; // Уникальный ID рынка у букмекера (например, "MARKET_987654")
    private String eventName; // Имя события (для удобства, хотя обычно связь через ID)
    private String externalEventId; // ID события, к которому относится рынок
    private String marketTypeName; // Тип рынка как есть (например, "TOTAL_GOALS", "HANDICAP", "INDIVIDUAL_TOTAL_HOME")
    private String periodName; // Период как есть (например, "FULL_TIME", "HALF_1")
    private BigDecimal line; // Значение линии (например, 2.5 для тотала, -1.5 для форы)
    private List<RawOutcome> outcomes; // Список исходов для этого рынка
    // Дополнительные поля, если необходимы (статус рынка и т.д.)
}