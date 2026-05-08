package com.oddscanner.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class RawEvent {
    private String externalId;
    private String sport;
    private String homeTeam;
    private String awayTeam;
    private Instant startsAt;
    private List<RawMarket> markets;
}
