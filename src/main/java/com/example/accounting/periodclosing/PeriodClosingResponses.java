package com.example.accounting.periodclosing;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PeriodClosingResponses {
    private PeriodClosingResponses() { }

    public record Blocker(String code, String title, String detail) { }

    public record Step(PeriodClosingStepType step, PeriodClosingStepStatus status,
                       BigDecimal amount, UUID voucherId, String inputFingerprint,
                       List<Blocker> blockers, OffsetDateTime updatedAt) { }

    public record TrialBalanceTotals(BigDecimal openingDebit, BigDecimal openingCredit,
                                     BigDecimal periodDebit, BigDecimal periodCredit,
                                     BigDecimal closingDebit, BigDecimal closingCredit,
                                     BigDecimal openingDifference, BigDecimal periodDifference,
                                     BigDecimal closingDifference, boolean balanced) { }

    public record Status(UUID ledgerId, UUID periodId, String periodCode, List<Step> steps,
                         List<Blocker> blockers, TrialBalanceTotals trialBalance,
                         boolean canClose) { }

    public record Settings(UUID ledgerId, UUID profitAccountId, UUID retainedEarningsAccountId,
                           UUID defaultProfitAccountId, UUID defaultRetainedEarningsAccountId,
                           long version) { }
}
