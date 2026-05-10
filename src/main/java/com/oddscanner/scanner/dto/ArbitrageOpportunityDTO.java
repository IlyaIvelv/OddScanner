// File: src/main/java/com/oddscanner/scanner/dto/ArbitrageOpportunityDTO.java

package com.oddscanner.scanner.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

// DTO для представления найденной арбитражной возможности
@Data // Заменяет @Getter, @Setter, @ToString, @EqualsAndHashCode, @RequiredArgsConstructor
@NoArgsConstructor // Генерирует конструктор без аргументов
@AllArgsConstructor // Генерирует конструктор со всеми аргументами
@Builder // Позволяет использовать Builder-паттерн
public class ArbitrageOpportunityDTO {

    private Long eventId; // ID внутреннего события
    private String marketSignature; // Уникальный ключ рынка (event_id, market_type, period, line)
    private BigDecimal profitPercentage; // Процент прибыли
    private List<ArbLegDTO> legs; // Ноги вилки (конкретные ставки)
    private LocalDateTime foundAt; // Время нахождения (установится в конструкторе или сеттере)

    // Конструктор, который устанавливает foundAt при создании
    public ArbitrageOpportunityDTO(Long eventId, String marketSignature, BigDecimal profitPercentage, List<ArbLegDTO> legs) {
        this.eventId = eventId;
        this.marketSignature = marketSignature;
        this.profitPercentage = profitPercentage;
        this.legs = legs;
        this.foundAt = LocalDateTime.now(); // Устанавливаем время создания
    }
}