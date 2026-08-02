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
                       String summary,
                       UUID cashFlowItemId,
                       BigDecimal quantity,
                       BigDecimal unitPrice,
                       List<@Valid Dimension> dimensions) {

        public Line(UUID accountId, String side, String currency, BigDecimal originalAmount,
                    BigDecimal exchangeRate, String summary) {
            this(accountId, side, currency, originalAmount, exchangeRate, summary,
                    null, null, null, List.of());
        }
    }

    public record Dimension(@NotNull UUID dimensionTypeId, @NotNull UUID dimensionValueId) {
    }

    public record Comment(@NotBlank String comment) {
    }

    public record Reason(@NotBlank String reason) {
    }
}
