package com.oddscanner.config;

import com.oddscanner.service.SchedulerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AppStartup implements ApplicationListener<ApplicationReadyEvent> {

    private final SchedulerService schedulerService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        try {
            schedulerService.start();
        } catch (Exception e) {
            log.error("Failed to start scheduler", e);
        }
    }
}
