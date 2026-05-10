// File: src/main/java/com/oddscanner/scheduler/ScannerTriggerService.java

package com.oddscanner.scheduler;

import com.oddscanner.ingestion.OddsUpdatedEvent;
import com.oddscanner.scanner.ArbFinderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async; // Если хотим асинхронно реагировать на событие
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ScannerTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerTriggerService.class);

    private final ArbFinderService arbFinderService;

    public ScannerTriggerService(ArbFinderService arbFinderService) {
        this.arbFinderService = arbFinderService;
    }

    /**
     * Запускает сканирование по расписанию.
     * Пример: каждые 30 секунд (30000 миллисекунд).
     * Можно настроить через application.properties/yml.
     */
    @Scheduled(fixedDelay = 30000) // Запускать каждые 30 секунд после завершения предыдущего выполнения
    // @Scheduled(cron = "0 0/1 * * * ?") // Альтернатива: запускать каждую минуту (cron выражение)
    public void triggerScheduledScan() {
        log.info("Scheduled scan triggered.");
        arbFinderService.triggerScan(); // Вызываем метод в ArbFinderService, который запускает сканирование
    }

    /**
     * Запускает сканирование сразу после обновления коэффициентов.
     * Аннотация @Async позволяет обрабатывать событие асинхронно (не блокировать публикатор).
     */
    @EventListener
    @Async // Комментировать, если не нужна асинхронность
    public void triggerScanOnOddsUpdate(OddsUpdatedEvent event) {
        log.info("OddsUpdatedEvent received for bookmaker: {}, event ID: {}. Triggering immediate scan.", event.getBookmakerCode(), event.getEventId());
        arbFinderService.triggerScan(); // Запускаем сканирование при получении события
    }
}