package com.example.accounting.voucher;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public final class VoucherRequests {

    private VoucherRequests() {
    }

    public record Create(@NotNull UUID periodId,
                         @NotNull LocalDate voucherDate,
                         @NotBlank String voucherType,
                         @NotBlank String voucherNumber,
                         String summary,
                         @NotEmpty List<@Valid Line> lines) {
    }

    public record Update(@NotNull Long expectedVersion,
                         @NotNull UUID periodId,
                         @NotNull LocalDate voucherDate,
                         @NotBlank String voucherType,
                         @NotBlank String voucherNumber,
                         String summary,
                         @NotEmpty List<@Valid Line> lines) {
    }

    public record Line(@NotNull UUID accountId,
                       @NotBlank @Pattern(regexp = "DEBIT|CREDIT") String side,
                       @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                       @NotNull BigDecimal originalAmount,
                       @NotNull BigDecimal exchangeRate,
                       String summary) {
    }

    public record Comment(@NotBlank String comment) {
    }

    public record Reason(@NotBlank String reason) {
    }
}
