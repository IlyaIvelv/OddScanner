package com.oddscanner.service;

import com.oddscanner.domain.ArbCandidate;
import com.oddscanner.domain.ArbLeg;
import com.oddscanner.domain.MatchedEvent;
import com.oddscanner.domain.MarketType;
import com.oddscanner.domain.RawMarket;
import com.oddscanner.domain.RawOutcome;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ArbCalculator {

    public List<ArbCandidate> calculate(
            MatchedEvent matched,
            Map<Long, String> bookmakerNames,
            Map<String, Long> marketIdsByKey,
            double minProfitPct
    ) {
        Set<MarketType> allTypes = new LinkedHashSet<>();
        matched.getMarketsByBookmaker().values().forEach(markets ->
                markets.forEach(m -> allTypes.add(m.getType())));

        List<ArbCandidate> candidates = new ArrayList<>();

        for (MarketType marketType : allTypes) {
            Map<String, List<ArbLeg>> byOutcome = new LinkedHashMap<>();

            matched.getMarketsByBookmaker().forEach((bookmakerId, markets) -> {
                markets.stream()
                        .filter(m -> m.getType() == marketType)
                        .findFirst()
                        .ifPresent(market -> {
                            String bookmakerName = bookmakerNames.getOrDefault(bookmakerId, String.valueOf(bookmakerId));
                            String key = bookmakerId + ":" + matched.getEventId() + ":" + marketType.toDbValue();
                            long marketId = marketIdsByKey.getOrDefault(key, 0L);

                            for (RawOutcome outcome : market.getOutcomes()) {
                                ArbLeg leg = new ArbLeg(
                                        bookmakerId, bookmakerName,
                                        marketId, market.getName(),
                                        outcome.getName(), outcome.getOdds(), 0
                                );
                                byOutcome.computeIfAbsent(outcome.getName(), k -> new ArrayList<>()).add(leg);
                            }
                        });
            });

            if (byOutcome.size() < 2) continue;

            List<ArbLeg> bestLegs = byOutcome.values().stream()
                    .map(legs -> legs.stream().max(Comparator.comparingDouble(ArbLeg::getOdds)).orElseThrow())
                    .toList();

            double impliedSum = bestLegs.stream().mapToDouble(l -> 1.0 / l.getOdds()).sum();
            if (impliedSum >= 1.0) continue;

            double profitPct = (1.0 / impliedSum - 1.0) * 100.0;
            if (profitPct < minProfitPct) continue;

            List<ArbLeg> legs = bestLegs.stream().map(l ->
                    new ArbLeg(l.getBookmakerId(), l.getBookmakerName(),
                            l.getMarketId(), l.getMarketName(),
                            l.getOutcomeName(), l.getOdds(),
                            (1.0 / l.getOdds() / impliedSum) * 100.0)
            ).toList();

            candidates.add(new ArbCandidate(matched.getEventId(), marketType, profitPct, legs));
        }

        return candidates;
    }
}
