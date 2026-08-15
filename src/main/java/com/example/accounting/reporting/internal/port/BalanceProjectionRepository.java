package com.example.accounting.reporting.internal.port;

import com.example.accounting.shared.balance.BalanceProjectionService;
import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.PeriodRange;
import java.math.BigDecimal;

public interface BalanceProjectionRepository {

    void appendVoucherEvent(BalanceProjectionService.VoucherEvent event);

    void appendOpeningBalanceEvent(BalanceProjectionService.OpeningBalanceEvent event);

    void requireOpenPeriod(UUID ledgerId, UUID periodId);

    void requireReadyForClose(UUID ledgerId, UUID periodId);

    void markReopened(UUID ledgerId, UUID periodId);

    void markFinalized(UUID ledgerId, UUID periodId);

    BalanceProjectionService.ProjectionStatus status(UUID ledgerId, String periodCode);

    BalanceProjectionService.ProjectionStatus status(UUID ledgerId, PeriodRange range);

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, PeriodRange range, boolean includeParents);

    List<ReportResponses.TrialBalanceLine> operatingTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents);

    BigDecimal openingBalance(UUID ledgerId, String periodCode, UUID accountId);

    BatchResult applyPendingBatchDetailed(int maxPeriods, boolean legacyTail);

    default boolean applyPendingBatch(int maxPeriods) {
        return applyPendingBatchDetailed(maxPeriods, false).processed();
    }

    /** Compatibility entry point retained for existing callers; event limits are no longer used. */
    default boolean applyPendingBatch(int maxEvents, int maxEventLines) {
        return applyPendingBatchDetailed(Integer.MAX_VALUE, true).processed();
    }

    void recordFailure();

    int cleanupAppliedEvents(OffsetDateTime cutoff, int batchSize);

    default int cleanupAppliedEvents(OffsetDateTime cutoff) {
        return cleanupAppliedEvents(cutoff, 1000);
    }

    CleanupMetrics cleanupMetrics(OffsetDateTime cutoff);

    ProjectionMetrics projectionMetrics();

    record BatchResult(boolean processed, int processedPeriods, int rebuiltRows) {
    }

    record CleanupMetrics(long pendingEvents, OffsetDateTime oldestCreatedAt) {
    }

    record ProjectionMetrics(long remainingDirtyPeriods, OffsetDateTime oldestPendingAt) {
    }
}
