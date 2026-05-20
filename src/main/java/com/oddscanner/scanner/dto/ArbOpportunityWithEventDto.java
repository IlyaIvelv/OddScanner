// dto/ArbOpportunityWithEventDto.java
package com.oddscanner.scanner.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

@Data
public class ArbOpportunityWithEventDto {
    private Long id;
    private Long eventId;
    private BigDecimal profitPct;
    private String status;
    private OffsetDateTime foundAt;
    private String homeTeam;
    private String awayTeam;
    private String eventUrl;
    private OffsetDateTime startTime;
    private List<ArbLegDto> legs;

    @Data
    public static class ArbLegDto {
        private Long bookmakerId;
        private String bookmakerCode;
        private String bookmakerName;
        private String outcomeKey;
        private BigDecimal odds;
        private BigDecimal stakeShare;
        private BigDecimal stakeAmount;
    }
}