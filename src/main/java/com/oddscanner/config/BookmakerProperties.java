package com.oddscanner.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "oddscanner")
public class BookmakerProperties {
    private Refresh refresh;
    private Map<String, BookmakerCfg> bookmakers;
    private Scanner scanner;
    private Normalization normalization;

    @Data
    public static class Refresh {
        private int globalSeconds = 30;
    }

    @Data
    public static class BookmakerCfg {
        private boolean enabled = true;
        private String baseUrl;
        private Integer refreshSeconds;
    }

    @Data
    public static class Scanner {
        private String triggerMode = "HYBRID"; // AFTER_INGEST, SCHEDULED, HYBRID
        private String cron = "*/30 * * * * *";
        private int debounceMs = 1500;
        private double minProfitPct = 0.5;
    }

    @Data
    public static class Normalization {
        private double teamSimilarityThreshold = 0.88;
        private int startTimeWindowMin = 5;
    }
}