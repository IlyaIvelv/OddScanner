package com.oddscanner.service;

import com.oddscanner.domain.ArbCandidate;
import com.oddscanner.domain.MatchedEvent;
import com.oddscanner.domain.RawEvent;
import com.oddscanner.domain.RawMarket;
import com.oddscanner.model.*;
import com.oddscanner.repository.*;
import com.oddscanner.scraper.BaseScraper;
import com.oddscanner.scraper.ScraperRegistry;
import com.oddscanner.service.EventMatcher.ScrapeResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArbFinderService {

    private final ScraperRegistry scraperRegistry;
    private final BookmakerRepository bookmakerRepository;
    private final EventRepository eventRepository;
    private final MarketRepository marketRepository;
    private final OutcomeRepository outcomeRepository;
    private final ArbRepository arbRepository;
    private final ScrapeJobRepository scrapeJobRepository;
    private final EventMatcher eventMatcher;
    private final ArbCalculator arbCalculator;

    @Transactional
    public int run(double minProfitPct) {
        log.info("ArbFinder: starting run");

        List<ScrapeResult> scrapeResults = scrapeAll();
        if (scrapeResults.size() < 2) {
            log.warn("ArbFinder: not enough bookmakers returned data");
            return 0;
        }

        List<MatchedEvent> matchedEvents = eventMatcher.match(scrapeResults);
        log.info("ArbFinder: matched {} events", matchedEvents.size());

        Map<Long, String> bookmakerNames = new HashMap<>();
        bookmakerRepository.findAll().forEach(bk -> bookmakerNames.put(bk.getId(), bk.getName()));

        int arbsFound = 0;

        for (MatchedEvent matched : matchedEvents) {
            Long eventId = upsertEvent(matched, scrapeResults);
            matched.setEventId(eventId);

            Map<String, Long> marketIdsByKey = upsertMarkets(eventId, matched.getMarketsByBookmaker());

            List<ArbCandidate> arbs = arbCalculator.calculate(matched, bookmakerNames, marketIdsByKey, minProfitPct);

            for (ArbCandidate arb : arbs) {
                Arb entity = new Arb();
                entity.setEventId(arb.getEventId());
                entity.setMarketType(arb.getMarketType().toDbValue());
                entity.setProfitPct(BigDecimal.valueOf(arb.getProfitPct()).setScale(4, RoundingMode.HALF_UP));
                entity.setLegs(arb.getLegs());
                entity.setActive(true);
                arbRepository.save(entity);
                arbsFound++;
                log.info("ArbFinder: arb found — {} | {} | profit={}%",
                        matched.getHomeTeam(), arb.getMarketType().getDisplayName(),
                        String.format("%.2f", arb.getProfitPct()));
            }
        }

        log.info("ArbFinder: run complete — {} arbs found", arbsFound);
        return arbsFound;
    }

    private List<ScrapeResult> scrapeAll() {
        List<ScrapeResult> results = new ArrayList<>();
        List<Bookmaker> activeBookmakers = bookmakerRepository.findAllByIsActiveTrue();

        for (BaseScraper scraper : scraperRegistry.getAll()) {
            Bookmaker bk = activeBookmakers.stream()
                    .filter(b -> b.getSlug().equals(scraper.getSlug()))
                    .findFirst().orElse(null);
            if (bk == null) continue;

            ScrapeJob job = new ScrapeJob();
            job.setBookmakerId(bk.getId());
            job.setStatus("running");
            job = scrapeJobRepository.save(job);

            try {
                log.info("ArbFinder: scraping {}", scraper.getName());
                List<RawEvent> events = scraper.fetchEvents();

                job.setStatus("success");
                job.setEventsFound(events.size());
                job.setFinishedAt(Instant.now());
                scrapeJobRepository.save(job);

                results.add(new ScrapeResult(bk.getId(), events));
            } catch (Exception e) {
                log.error("ArbFinder: scrape failed for {} — {}", scraper.getName(), e.getMessage());
                job.setStatus("failed");
                job.setError(e.getMessage());
                job.setFinishedAt(Instant.now());
                scrapeJobRepository.save(job);
            }
        }
        return results;
    }

    private Long upsertEvent(MatchedEvent matched, List<ScrapeResult> results) {
        return eventRepository.findByHomeTeamAndAwayTeamAndSport(
                matched.getHomeTeam(), matched.getAwayTeam(), matched.getSport()
        ).map(Event::getId).orElseGet(() -> {
            Map<String, String> externalIds = new HashMap<>();
            results.forEach(r -> r.events().stream()
                    .filter(e -> e.getHomeTeam().equals(matched.getHomeTeam()) &&
                                 e.getAwayTeam().equals(matched.getAwayTeam()))
                    .findFirst()
                    .ifPresent(e -> externalIds.put("bk_" + r.bookmakerId(), e.getExternalId())));

            Event event = new Event();
            event.setHomeTeam(matched.getHomeTeam());
            event.setAwayTeam(matched.getAwayTeam());
            event.setSport(matched.getSport());
            event.setStartsAt(matched.getStartsAt());
            event.setExternalIds(externalIds);
            return eventRepository.save(event).getId();
        });
    }

    private Map<String, Long> upsertMarkets(Long eventId, Map<Long, List<RawMarket>> marketsByBookmaker) {
        Map<String, Long> keyToId = new HashMap<>();

        marketsByBookmaker.forEach((bookmakerId, markets) -> {
            for (RawMarket market : markets) {
                String typeStr = market.getType().toDbValue();
                Market entity = marketRepository
                        .findByEventIdAndBookmakerIdAndType(eventId, bookmakerId, typeStr)
                        .orElseGet(() -> {
                            Market m = new Market();
                            m.setEventId(eventId);
                            m.setBookmakerId(bookmakerId);
                            m.setType(typeStr);
                            m.setName(market.getName());
                            m.setLine(market.getLine());
                            return marketRepository.save(m);
                        });

                entity.setUpdatedAt(Instant.now());
                marketRepository.save(entity);

                for (var outcome : market.getOutcomes()) {
                    outcomeRepository.findByMarketIdAndName(entity.getId(), outcome.getName())
                            .ifPresentOrElse(
                                    o -> {
                                        o.setOdds(BigDecimal.valueOf(outcome.getOdds()).setScale(4, RoundingMode.HALF_UP));
                                        o.setUpdatedAt(Instant.now());
                                        outcomeRepository.save(o);
                                    },
                                    () -> {
                                        Outcome o = new Outcome();
                                        o.setMarketId(entity.getId());
                                        o.setName(outcome.getName());
                                        o.setOdds(BigDecimal.valueOf(outcome.getOdds()).setScale(4, RoundingMode.HALF_UP));
                                        outcomeRepository.save(o);
                                    }
                            );
                }

                keyToId.put(bookmakerId + ":" + eventId + ":" + typeStr, entity.getId());
            }
        });

        return keyToId;
    }
}
