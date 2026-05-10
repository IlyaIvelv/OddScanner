package com.oddscanner.scanner.dto;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbLegResponseDTO {

    private Long id;
    private Long arbId;
    private Long outcomeId;
    private String outcomeKey; // Ключ исхода (OVER, UNDER, HOME_WIN, etc.) - из связанной таблицы outcomes
    private Long marketId;
    private BigDecimal odds;
    private BigDecimal stakeShare;
    private Boolean isActive;
}