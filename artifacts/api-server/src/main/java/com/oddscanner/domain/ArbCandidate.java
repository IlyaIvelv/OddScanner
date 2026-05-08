package com.oddscanner.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class ArbCandidate {
    private Long eventId;
    private MarketType marketType;
    private double profitPct;
    private List<ArbLeg> legs;
}
