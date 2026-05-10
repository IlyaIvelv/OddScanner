// File: src/main/java/com/oddscanner/scanner/ArbitrageFoundEvent.java

package com.oddscanner.scanner;

import com.oddscanner.scanner.dto.ArbitrageOpportunityDTO;

import java.time.LocalDateTime;

// Событие, сигнализирующее о нахождении арбитражной возможности.
// Публикуется ArbFinderService после сохранения новой вилки.
public class ArbitrageFoundEvent {

    private final ArbitrageOpportunityDTO opportunity;
    private final LocalDateTime timestamp;

    public ArbitrageFoundEvent(ArbitrageOpportunityDTO opportunity) {
        this.opportunity = opportunity;
        this.timestamp = LocalDateTime.now(); // Время публикации события
    }

    public ArbitrageOpportunityDTO getOpportunity() {
        return opportunity;
    }

    public LocalDateTime getTimestamp() {
        return timestamp;
    }
}