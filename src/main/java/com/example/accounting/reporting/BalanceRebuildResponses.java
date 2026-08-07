package com.example.accounting.reporting;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BalanceRebuildResponses {

    private BalanceRebuildResponses() {
    }

    public record Job(UUID id, UUID ledgerId, String periodFrom, String periodTo, String status,
                      String reason, UUID requestedBy, int processedPeriods, int totalPeriods,
                      int differenceCount, OffsetDateTime createdAt, OffsetDateTime startedAt,
                      OffsetDateTime completedAt, String errorCode, String errorMessage) {
    }
}
