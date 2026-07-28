package com.example.accounting.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class LedgerRequests {

    private LedgerRequests() {
    }

    public record Create(@NotBlank String name,
                         @NotBlank String accountingStandardCode,
                         @NotBlank String accountingStandardVersion,
                         @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
                         @NotNull LocalDate startDate,
                         Boolean approvalEnabled) {
    }

    public record AddMember(@NotNull UUID userId, @NotNull LedgerRole role) {
    }

    public record UpdateMember(@NotNull LedgerRole role, @NotNull MembershipStatus status) {
    }

    public record PeriodAction(@NotBlank String reason) {
    }

    public record DimensionTypeCreate(@NotBlank String code, @NotBlank String name, Boolean required) {
    }

    public record DimensionValueCreate(@NotBlank String code, @NotBlank String name) {
    }

    public record OpeningBalances(@NotEmpty List<@Valid OpeningBalanceLine> lines) {
    }

    public record OpeningBalanceLine(@NotNull UUID accountId,
                                     @NotNull UUID periodId,
                                     @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                     String dimensionKey,
                                     @NotNull BigDecimal debitOriginal,
                                     @NotNull BigDecimal creditOriginal,
                                     @NotNull BigDecimal exchangeRate) {
    }
}
