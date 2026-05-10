package com.oddscanner.scanner.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.oddscanner.scanner.dto.ArbLegResponseDTO;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageOpportunityResponseDTO {

    private Long id;
    private Long eventId;
    private String marketSignature;
    private BigDecimal profitPercentage;
    private String status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime foundAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expiredAt;

    private List<ArbLegResponseDTO> legs;
}