package com.example.accounting.reporting.internal.port;

import com.example.accounting.shared.balance.BalanceProjectionService;
import java.util.UUID;
import java.util.List;
import java.time.OffsetDateTime;
import com.example.accounting.reporting.ReportResponses;

public interface BalanceProjectionRepository {

    void appendAndApplyVoucherEvent(BalanceProjectionService.VoucherEvent event);

    void appendOpeningBalanceEvent(BalanceProjectionService.OpeningBalanceEvent event);

    void requireOpenPeriod(UUID ledgerId, UUID periodId);

    void requireReadyForClose(UUID ledgerId, UUID periodId);

    void markReopened(UUID ledgerId, UUID periodId);

    BalanceProjectionService.ProjectionStatus status(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode);

    boolean applyPendingBatch(int maxEvents, int maxEventLines);

    void recordFailure();

    int cleanupAppliedEvents(OffsetDateTime cutoff);
}
