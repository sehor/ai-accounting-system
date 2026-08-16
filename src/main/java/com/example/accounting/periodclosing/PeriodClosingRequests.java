package com.example.accounting.periodclosing;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public final class PeriodClosingRequests {
    private PeriodClosingRequests() { }

    public record SettingsPatch(UUID profitAccountId, UUID retainedEarningsAccountId) { }

    public record Generate(@NotNull PeriodClosingStepType step) { }

    public record Reset(@NotBlank @Size(max = 1000) String reason) { }
}
