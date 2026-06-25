package com.oddscanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.SchedulingConfigurer;
import org.springframework.scheduling.config.ScheduledTaskRegistrar;
import java.util.concurrent.Executors;

@Configuration
@EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {

    @Value("${parser.thread-pool-size:5}")
    private int threadPoolSize;

    @Override
    public void configureTasks(ScheduledTaskRegistrar taskRegistrar) {
        // Пул из 5 потоков для параллельного запуска парсеров
        taskRegistrar.setScheduler(Executors.newScheduledThreadPool(5));
    }
}