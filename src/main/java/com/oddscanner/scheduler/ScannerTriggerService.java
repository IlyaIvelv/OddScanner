package com.oddscanner.scheduler;

import com.oddscanner.ingestion.OddsUpdatedEvent;
import com.oddscanner.scanner.ArbFinderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class ScannerTriggerService {

    private static final Logger log = LoggerFactory.getLogger(ScannerTriggerService.class);
    private static final long MIN_SCAN_INTERVAL_MS = 30000; // Минимум 30 секунд между сканами

    private final ArbFinderService arbFinderService;

    // Флаг, что сканирование уже выполняется
    private final AtomicBoolean isScanning = new AtomicBoolean(false);

    // Время последнего сканирования
    private final AtomicLong lastScanTime = new AtomicLong(0);

    public ScannerTriggerService(ArbFinderService arbFinderService) {
        this.arbFinderService = arbFinderService;
    }

    /**
     * Запускает сканирование по расписанию каждые 30 секунд
     */
    @Scheduled(fixedDelay = 30000)
    public void triggerScheduledScan() {
        long now = System.currentTimeMillis();
        long lastScan = lastScanTime.get();

        // Если прошло меньше интервала - пропускаем
        if (now - lastScan < MIN_SCAN_INTERVAL_MS && lastScan > 0) {
            log.debug("Scheduled scan skipped - last scan was {} ms ago", now - lastScan);
            return;
        }

        log.info("Scheduled scan triggered.");
        performScan();
    }

    /**
     * Запускает сканирование после обновления коэффициентов
     */
    @EventListener
    @Async
    public void triggerScanOnOddsUpdate(OddsUpdatedEvent event) {
        long now = System.currentTimeMillis();
        long lastScan = lastScanTime.get();

        // Проверяем, не слишком ли часто запускаем
        if (now - lastScan < MIN_SCAN_INTERVAL_MS && lastScan > 0) {
            // Для отладки можно включить, но в проде лучше выключить
            // log.debug("Odds update scan skipped - last scan was {} ms ago, bookmaker: {}, event: {}",
            //     now - lastScan, event.getBookmakerCode(), event.getEventId());
            return;
        }

        log.info("OddsUpdatedEvent received for bookmaker: {}, event ID: {}. Triggering immediate scan.",
                event.getBookmakerCode(), event.getEventId());
        performScan();
    }

    /**
     * Выполняет сканирование с защитой от параллельных запусков
     */
    private void performScan() {
        // Проверяем, не запущено ли уже сканирование
        if (!isScanning.compareAndSet(false, true)) {
            log.warn("Scan already in progress, skipping duplicate trigger");
            return;
        }

        try {
            long startTime = System.currentTimeMillis();
            arbFinderService.triggerScan();
            long duration = System.currentTimeMillis() - startTime;
            lastScanTime.set(System.currentTimeMillis());
            log.info("Scan completed successfully in {} ms", duration);
        } catch (Exception e) {
            log.error("Error during scan execution", e);
        } finally {
            isScanning.set(false);
        }
    }
}