// File: src/main/java/com/oddscanner/scanner/dto/ArbLegDTO.java
package com.oddscanner.scanner.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ArbLegDTO.ArbLegDTOBuilder.class)
public class ArbLegDTO {
    Long outcomeId;
    Long marketId;
    String outcomeKey;
    BigDecimal odds;
    BigDecimal stakeShare;

    @JsonCreator
    private ArbLegDTO(@JsonProperty("outcomeId") Long outcomeId,
                      @JsonProperty("marketId") Long marketId,
                      @JsonProperty("outcomeKey") String outcomeKey,
                      @JsonProperty("odds") BigDecimal odds,
                      @JsonProperty("stakeShare") BigDecimal stakeShare) {
        this.outcomeId = outcomeId;
        this.marketId = marketId;
        this.outcomeKey = outcomeKey;
        this.odds = odds;
        this.stakeShare = stakeShare;
    }

    // ИСПРАВЛЕНО: теперь возвращает правильный билдер
    public static ArbLegDTOBuilder builder() {
        return new ArbLegDTOBuilder();
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class ArbLegDTOBuilder {
    }
}