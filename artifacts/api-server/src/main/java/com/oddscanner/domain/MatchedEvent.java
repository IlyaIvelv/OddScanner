package com.oddscanner.domain;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class MatchedEvent {
    private Long eventId;
    private String homeTeam;
    private String awayTeam;
    private String sport;
    private Instant startsAt;
    private Map<Long, List<RawMarket>> marketsByBookmaker;
}
