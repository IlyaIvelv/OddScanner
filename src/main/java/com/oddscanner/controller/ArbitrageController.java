package com.oddscanner.controller;

import com.oddscanner.service.ArbitrageService;
import com.oddscanner.service.ArbitrageService.ArbitrageOpportunity;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/arbitrage")
@RequiredArgsConstructor
public class ArbitrageController {

    private final ArbitrageService arbitrageService;

    @GetMapping("/find")
    public List<ArbitrageOpportunity> findArbitrages() {
        return arbitrageService.findArbitrages();
    }
}