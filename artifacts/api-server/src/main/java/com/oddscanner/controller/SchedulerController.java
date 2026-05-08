package com.oddscanner.controller;

import com.oddscanner.model.SchedulerConfig;
import com.oddscanner.service.SchedulerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/scheduler")
@RequiredArgsConstructor
public class SchedulerController {

    private final SchedulerService schedulerService;

    @GetMapping
    public Map<String, Object> getStatus() {
        SchedulerConfig config = schedulerService.getOrCreateConfig();
        return buildResponse(config);
    }

    @PutMapping
    public Map<String, Object> updateConfig(@Valid @RequestBody UpdateRequest body) {
        SchedulerConfig config = schedulerService.updateConfig(
                body.cronExpression(), body.isEnabled(), body.minProfitPct()
        );
        return buildResponse(config);
    }

    private Map<String, Object> buildResponse(SchedulerConfig config) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("isRunning", schedulerService.isCurrentlyRunning());
        map.put("isScheduled", schedulerService.isScheduled());
        map.put("cronExpression", config.getCronExpression());
        map.put("isEnabled", config.isEnabled());
        map.put("minProfitPct", config.getMinProfitPct());
        map.put("lastRunAt", config.getLastRunAt());
        return map;
    }

    public record UpdateRequest(
            @NotBlank String cronExpression,
            boolean isEnabled,
            @Min(0) @Max(100) int minProfitPct
    ) {}
}
