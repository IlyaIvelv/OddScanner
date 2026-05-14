// File: src/main/java/com/oddscanner/scanner/dto/ArbitrageOpportunityDTO.java
package com.oddscanner.scanner.dto;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.fasterxml.jackson.databind.annotation.JsonPOJOBuilder;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder(toBuilder = true)
@JsonDeserialize(builder = ArbitrageOpportunityDTO.ArbitrageOpportunityDTOBuilder.class)
public class ArbitrageOpportunityDTO {
    Long eventId;
    String marketSignature;
    BigDecimal profitPercentage;
    LocalDateTime foundAt;
    List<ArbLegDTO> legs;

    @JsonCreator
    private ArbitrageOpportunityDTO(@JsonProperty("eventId") Long eventId,
                                    @JsonProperty("marketSignature") String marketSignature,
                                    @JsonProperty("profitPercentage") BigDecimal profitPercentage,
                                    @JsonProperty("foundAt") LocalDateTime foundAt,
                                    @JsonProperty("legs") List<ArbLegDTO> legs) {
        this.eventId = eventId;
        this.marketSignature = marketSignature;
        this.profitPercentage = profitPercentage;
        this.foundAt = foundAt != null ? foundAt : LocalDateTime.now();
        this.legs = legs;
    }

    // Дополнительный конструктор для упрощения создания в ArbCalculator
    public ArbitrageOpportunityDTO(Long eventId, String marketSignature, BigDecimal profitPercentage, List<ArbLegDTO> legs) {
        this(eventId, marketSignature, profitPercentage, LocalDateTime.now(), legs);
    }

    @JsonPOJOBuilder(withPrefix = "")
    public static class ArbitrageOpportunityDTOBuilder {
    }
}