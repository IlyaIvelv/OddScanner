// File: src/main/java/com/oddscanner/bookmaker/api/RawOutcome.java

package com.oddscanner.bookmaker.api;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * DTO для представления "сырого" исхода ставки от букмекера.
 * Например, "Домашняя победа", "Тотал больше 2.5", "Фора -1.5 на хозяев".
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RawOutcome {
    private String externalMarketId; // ID рынка, к которому относится исход
    private String outcomeKeyName; // Ключ исхода как есть (например, "HOME_WIN", "OVER", "UNDER", "HOME_HANDICAP_WIN")
    private String outcomeValueDescription; // Описание исхода как есть (например, "1", "X", "2", "Победа 1", "Обе забьют - Да")
    private BigDecimal odds; // Коэффициент
    private boolean isActive; // Активна ли ставка
    // Дополнительные поля, если необходимы (лимиты, тип линии и т.д.)
}