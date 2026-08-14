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

    boolean applyPendingBatch(int maxEvents, int maxEventLines);

    void recordFailure();

    int cleanupAppliedEvents(OffsetDateTime cutoff);
}
