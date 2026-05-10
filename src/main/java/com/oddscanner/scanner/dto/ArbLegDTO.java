
package com.oddscanner.scanner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

// DTO для представления одной "ноги" вилки
@Data // Заменяет @Getter, @Setter, @ToString, @EqualsAndHashCode, @RequiredArgsConstructor
@NoArgsConstructor // Генерирует конструктор без аргументов
@AllArgsConstructor // Генерирует конструктор со всеми аргументами
@Builder // Позволяет использовать Builder-паттерн
public class ArbLegDTO {

    private Long outcomeId; // ID исхода
    private Long marketId; // ID рынка
    private String outcomeKey; // Ключ исхода (OVER, UNDER, HOME, AWAY, etc.)
    private BigDecimal odds; // Коэффициент
    private BigDecimal stakeShare; // Доля ставки (например, 0.5 для 50%)
}