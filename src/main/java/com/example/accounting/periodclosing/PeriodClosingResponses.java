package com.example.accounting.periodclosing;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class PeriodClosingResponses {
    private PeriodClosingResponses() { }

    @Schema(name = "PeriodClosingBlocker")
    public record Blocker(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String code,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String title,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String detail) { }

    @Schema(name = "PeriodClosingStep")
    public record Step(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PeriodClosingStepType step,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) PeriodClosingStepStatus status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal amount,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID voucherId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String inputFingerprint,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Blocker> blockers,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime updatedAt) { }

    @Schema(name = "PeriodClosingTrialBalance")
    public record TrialBalanceTotals(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal openingDebit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal openingCredit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal periodDebit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal periodCredit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal closingDebit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal closingCredit,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal openingDifference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal periodDifference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) BigDecimal closingDifference,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean balanced) { }

    @Schema(name = "PeriodClosingStatus")
    public record Status(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ledgerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID periodId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String periodCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Step> steps,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) List<Blocker> blockers,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) TrialBalanceTotals trialBalance,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean canClose) { }

    @Schema(name = "PeriodClosingSettings")
    public record Settings(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ledgerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID profitAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID retainedEarningsAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID defaultProfitAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) UUID defaultRetainedEarningsAccountId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long version) { }
}
