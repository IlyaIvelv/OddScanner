package com.oddscanner.parser;

import com.oddscanner.repository.EventRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

public abstract class AbstractBookmakerParser implements BookmakerParser {
    private final MeterRegistry meterRegistry;
    protected final EventRepository eventRepository;
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected AbstractBookmakerParser(MeterRegistry meterRegistry, EventRepository eventRepository) {
        this.meterRegistry = meterRegistry;
        this.eventRepository = eventRepository;
    }

    @Scheduled(fixedDelay = 60_000)
    public final void scheduledRun() {
        String name = getName();

        // Проверяем, активен ли букмекер в БД
        if (!eventRepository.isBookmakerActive(name)) {
            log.debug("[{}] Парсер неактивен, пропускаю", name);
            return;
        }

        Timer.Sample sample = Timer.start(meterRegistry);
        String status = "success";

        try {
            log.info("[{}] Начинаю парсинг...", name);
            var events = doParse();

            meterRegistry.counter("parser.events.total", "bookmaker", name)
                    .increment(events.size());
            log.info("[{}] Успешно спарсено {} событий", name, events.size());

        } catch (Exception e) {
            status = "error";
            log.error("[{}] Ошибка парсинга: {}", name, e.getMessage(), e);
        } finally {
            sample.stop(Timer.builder("parser.duration.seconds")
                    .tag("bookmaker", name)
                    .tag("status", status)
                    .register(meterRegistry));

            meterRegistry.counter("parser.runs.total", "bookmaker", name, "status", status)
                    .increment();
        }
    }

}