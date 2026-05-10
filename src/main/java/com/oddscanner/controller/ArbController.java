// File: src/main/java/com/oddscanner/controller/ArbController.java

package com.oddscanner.controller;

import com.oddscanner.generated.tables.records.ArbOpportunitiesRecord;
import com.oddscanner.generated.tables.records.ArbLegsRecord;
import com.oddscanner.repository.ArbLegRepository;
import com.oddscanner.repository.ArbOpportunityRepository;
import com.oddscanner.repository.OutcomeRepository; // Добавим, если нужно получить outcomeKey
import com.oddscanner.scanner.dto.ArbitrageOpportunityResponseDTO; // Используем правильный пакет
import com.oddscanner.scanner.dto.ArbLegResponseDTO; // Используем правильный пакет
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/arbs")
@Tag(name = "Arbitrage Opportunities", description = "API для получения информации о найденных арбитражных возможностях")
public class ArbController {

    private static final Logger log = LoggerFactory.getLogger(ArbController.class);

    private final ArbOpportunityRepository arbOpportunityRepo;
    private final ArbLegRepository arbLegRepo;
    private final OutcomeRepository outcomeRepo; // Добавим репозиторий для получения outcomeKey

    public ArbController(ArbOpportunityRepository arbOpportunityRepo,
                         ArbLegRepository arbLegRepo,
                         OutcomeRepository outcomeRepo) { // Внедрим OutcomeRepository
        this.arbOpportunityRepo = arbOpportunityRepo;
        this.arbLegRepo = arbLegRepo;
        this.outcomeRepo = outcomeRepo;
    }

    @GetMapping
    @Operation(
            summary = "Получить все активные вилки",
            description = "Возвращает список всех найденных активных арбитражных возможностей."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Список вилок успешно получен",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ArbitrageOpportunityResponseDTO.class))
            )
    )
    public ResponseEntity<List<ArbitrageOpportunityResponseDTO>> getAllActiveArbs() {
        log.info("GET /api/v1/arbs called");

        List<ArbOpportunitiesRecord> arbs = arbOpportunityRepo.findAll().stream()
                .filter(record -> "ACTIVE".equals(record.getStatus()))
                .collect(Collectors.toList());

        List<ArbitrageOpportunityResponseDTO> dtos = arbs.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        log.info("Returning {} active arbitrage opportunities", dtos.size());
        return ResponseEntity.ok(dtos);
    }

    @GetMapping("/sorted-by-profit-desc")
    @Operation(
            summary = "Получить все вилки, отсортированные по проценту прибыли (DESC)",
            description = "Возвращает список всех арбитражных возможностей, отсортированный по убыванию процента прибыли."
    )
    @ApiResponse(
            responseCode = "200",
            description = "Список вилок успешно получен и отсортирован",
            content = @Content(
                    mediaType = "application/json",
                    array = @ArraySchema(schema = @Schema(implementation = ArbitrageOpportunityResponseDTO.class))
            )
    )
    public ResponseEntity<List<ArbitrageOpportunityResponseDTO>> getAllArbsSortedByProfitDesc() {
        log.info("GET /api/v1/arbs/sorted-by-profit-desc called");

        List<ArbOpportunitiesRecord> arbs = arbOpportunityRepo.findAllSortedByProfitDesc();

        List<ArbitrageOpportunityResponseDTO> dtos = arbs.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());

        log.info("Returning {} arbitrage opportunities sorted by profit", dtos.size());
        return ResponseEntity.ok(dtos);
    }

    private ArbitrageOpportunityResponseDTO convertToDto(ArbOpportunitiesRecord record) {
        ArbitrageOpportunityResponseDTO dto = new ArbitrageOpportunityResponseDTO();
        dto.setId(record.getId());
        dto.setEventId(record.getEventId());
        dto.setMarketSignature(record.getMarketSignature());
        dto.setProfitPercentage(record.getProfitPct());
        dto.setStatus(record.getStatus());
        dto.setFoundAt(record.getFoundAt() != null ? record.getFoundAt().toLocalDateTime() : null);
        dto.setExpiredAt(record.getExpiredAt() != null ? record.getExpiredAt().toLocalDateTime() : null);

        // Загружаем ноги
        List<ArbLegsRecord> legsDb = arbLegRepo.findByArbId(record.getId());
        List<ArbLegResponseDTO> legDtos = legsDb.stream()
                .map(leg -> {
                    ArbLegResponseDTO legDto = new ArbLegResponseDTO();
                    legDto.setId(leg.getId());
                    legDto.setArbId(leg.getArbId());
                    legDto.setOutcomeId(leg.getOutcomeId());
                    legDto.setMarketId(leg.getMarketId());
                    legDto.setOdds(leg.getOdds());
                    legDto.setStakeShare(leg.getStakeShare());
                    // legDto.setIsActive(leg.getIsActive()); // УДАЛИТЬ ЭТУ СТРОКУ - столбца is_active нет в arb_legs
                    legDto.setIsActive(null); // Устанавливаем как null, если не используется или берётся из другого источника

                    // Загружаем outcomeKey из outcomes, если нужно
                    if (leg.getOutcomeId() != null) {
                        Optional<com.oddscanner.generated.tables.records.OutcomesRecord> outcomeOpt = outcomeRepo.findById(leg.getOutcomeId());
                        if (outcomeOpt.isPresent()) {
                            legDto.setOutcomeKey(outcomeOpt.get().getOutcomeKey());
                        } else {
                            log.warn("Outcome with ID {} referenced by ArbLeg {} not found.", leg.getOutcomeId(), leg.getId());
                            legDto.setOutcomeKey(null); // или какое-то значение по умолчанию
                        }
                    } else {
                        legDto.setOutcomeKey(null);
                    }
                    return legDto;
                })
                .collect(Collectors.toList());

        dto.setLegs(legDtos);
        return dto;
    }
}