package com.oddscanner.service;

import com.oddscanner.model.SchedulerConfig;
import com.oddscanner.repository.SchedulerConfigRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.support.CronTrigger;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final ArbFinderService arbFinderService;
    private final SchedulerConfigRepository schedulerConfigRepository;
    private final TaskScheduler taskScheduler;

    private ScheduledFuture<?> scheduledTask;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public void start() {
        SchedulerConfig config = getOrCreateConfig();
        if (!config.isEnabled()) {
            log.info("Scheduler: disabled, skipping start");
            return;
        }
        scheduleWith(config.getCronExpression(), config.getMinProfitPct());
    }

    public SchedulerConfig updateConfig(String cronExpression, boolean isEnabled, int minProfitPct) {
        SchedulerConfig config = getOrCreateConfig();
        config.setCronExpression(cronExpression);
        config.setEnabled(isEnabled);
        config.setMinProfitPct(minProfitPct);
        config.setUpdatedAt(Instant.now());
        config = schedulerConfigRepository.save(config);

        stop();
        if (isEnabled) scheduleWith(cronExpression, minProfitPct);

        log.info("Scheduler: config updated — cron={} enabled={} minProfit={}%",
                cronExpression, isEnabled, minProfitPct);
        return config;
    }

    public synchronized void stop() {
        if (scheduledTask != null) {
            scheduledTask.cancel(false);
            scheduledTask = null;
            log.info("Scheduler: stopped");
        }
    }

    public int triggerNow() {
        return runArbFinder(getOrCreateConfig().getMinProfitPct());
    }

    private synchronized void scheduleWith(String cronExpression, int minProfitPct) {
        try {
            CronTrigger trigger = new CronTrigger(cronExpression);
            scheduledTask = taskScheduler.schedule(
                    () -> runArbFinder(minProfitPct),
                    trigger
            );
            log.info("Scheduler: started — cron={}", cronExpression);
        } catch (Exception e) {
            log.error("Scheduler: invalid cron expression — {}", cronExpression);
        }
    }

    private int runArbFinder(int minProfitPct) {
        if (!isRunning.compareAndSet(false, true)) {
            log.warn("Scheduler: previous run still in progress, skipping");
            return 0;
        }
        try {
            int count = arbFinderService.run(minProfitPct);
            SchedulerConfig config = getOrCreateConfig();
            config.setLastRunAt(Instant.now());
            schedulerConfigRepository.save(config);
            return count;
        } finally {
            isRunning.set(false);
        }
    }

    public SchedulerConfig getOrCreateConfig() {
        return schedulerConfigRepository.findAll().stream()
                .findFirst()
                .orElseGet(() -> {
                    SchedulerConfig c = new SchedulerConfig();
                    return schedulerConfigRepository.save(c);
                });
    }

    public boolean isScheduled() {
        return scheduledTask != null && !scheduledTask.isCancelled();
    }

    public boolean isCurrentlyRunning() {
        return isRunning.get();
    }
}
