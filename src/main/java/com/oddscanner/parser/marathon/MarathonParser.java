package com.oddscanner.parser.marathon;

import com.oddscanner.parser.AbstractBookmakerParser;
import com.oddscanner.parser.RawEvent;
import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
public class MarathonParser extends AbstractBookmakerParser {
    private final EventRepository eventRepository;

    public MarathonParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        super(meterRegistry, eventRepository);
        this.eventRepository = eventRepository;
    }

    @Override
    public String getName() {
        return "Marathon";
    }

    @Override
    public List<RawEvent> doParse() throws Exception {
        List<RawEvent> events = new ArrayList<>();
//
//        // TODO: Реальная логика парсинга Marathon
//        // Для MVP создадим тестовые данные
//
//        RawEvent testEvent = new RawEvent(
//                "MARATHON_TEST_" + System.currentTimeMillis(),
//                "Хоккей",
//                "НХЛ",
//                "Торонто",
//                "Монреаль",
//                LocalDateTime.now().plusHours(3),
//                List.of(
//                        new RawEvent.RawMarket("1X2", List.of(
//                                new RawEvent.RawOutcome("П1", new BigDecimal("1.75")),
//                                new RawEvent.RawOutcome("X", new BigDecimal("4.00")),
//                                new RawEvent.RawOutcome("П2", new BigDecimal("4.20"))
//                        ))
//                ),
//                ""
//
//        );
//
//        events.add(testEvent);
//        eventRepository.saveEvents("MARATHON", events);
//
//        log.info("[Marathon] Сохранено {} событий", events.size());
        return events;
    }
}