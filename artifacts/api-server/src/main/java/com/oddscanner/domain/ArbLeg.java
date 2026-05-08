package com.oddscanner.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArbLeg {
    private Long bookmakerId;
    private String bookmakerName;
    private Long marketId;
    private String marketName;
    private String outcomeName;
    private double odds;
    private double stakePercent;
}
