package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class BalanceRebuildResponses {

    private BalanceRebuildResponses() {
    }

    @Schema(name = "BalanceRebuildJob", requiredProperties = {
            "id", "ledgerId", "periodFrom", "periodTo", "status", "reason", "requestedBy",
            "processedPeriods", "totalPeriods", "differenceCount", "createdAt", "startedAt",
            "completedAt", "errorCode", "errorMessage"})
    public record Job(UUID id, UUID ledgerId, String periodFrom, String periodTo, String status,
                      String reason, UUID requestedBy, int processedPeriods, int totalPeriods,
                      int differenceCount, OffsetDateTime createdAt,
                      @Schema(nullable = true) OffsetDateTime startedAt,
                      @Schema(nullable = true) OffsetDateTime completedAt,
                      @Schema(nullable = true) String errorCode,
                      @Schema(nullable = true) String errorMessage) {
    }
}
