package com.example.accounting.periodclosing;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public final class PeriodClosingRequests {
    private PeriodClosingRequests() { }

    public record SettingsPatch(UUID profitAccountId, UUID retainedEarningsAccountId) { }

    public record Generate(@NotNull PeriodClosingStepType step) { }
}
