// dto/ArbOpportunityWithDetailsDto.java
package com.oddscanner.scanner.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ArbOpportunityWithDetailsDto {
    private Long id;
    private Long eventId;
    private BigDecimal profitPercentage;
    private String status;
    private OffsetDateTime foundAt;

    // Детали события
    private String homeTeam;
    private String awayTeam;
    private String eventUrl;
    private OffsetDateTime startTime;
    private String leagueName;

    // Ноги вилки
    private List<ArbLegDetailsDto> legs;

    @Data
    public static class ArbLegDetailsDto {
        private String bookmakerCode;
        private String bookmakerName;
        private String outcomeKey;
        private BigDecimal odds;
        private BigDecimal stakeShare;
        private BigDecimal stakeAmount;
    }
}