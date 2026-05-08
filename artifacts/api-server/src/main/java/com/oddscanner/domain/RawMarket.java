package com.oddscanner.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawMarket {
    private String externalId;
    private MarketType type;
    private String name;
    private String line;
    private List<RawOutcome> outcomes;
}
