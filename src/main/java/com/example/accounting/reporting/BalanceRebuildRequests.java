package com.example.accounting.reporting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class BalanceRebuildRequests {

    private BalanceRebuildRequests() {
    }

    public record Create(
            @Pattern(regexp = "^$|\\d{4}-\\d{2}", message = "periodFrom must use YYYY-MM") String periodFrom,
            @Pattern(regexp = "^$|\\d{4}-\\d{2}", message = "periodTo must use YYYY-MM") String periodTo,
            @NotBlank(message = "reason is required") String reason) {
    }
}
