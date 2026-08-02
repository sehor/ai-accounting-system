package com.example.accounting.ledger;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
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
                         Boolean approvalEnabled,
                         @Valid AccountCodeRule accountCodeRule) {

        public Create(String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, Boolean approvalEnabled) {
            this(name, accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, null);
        }
    }

    public record AddMember(@NotNull UUID userId, @NotNull LedgerRole role) {
    }

    public record UpdateMember(@NotNull LedgerRole role, @NotNull MembershipStatus status) {
    }

    public record AccountCreate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String code,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Pattern(regexp = "ASSET|LIABILITY|EQUITY|COST|REVENUE|EXPENSE") String category,
            @NotBlank @Pattern(regexp = "DEBIT|CREDIT") String normalBalance,
            UUID parentId,
            Boolean cashFlowRequired,
            UUID defaultCashFlowItemId,
            Boolean quantityEnabled,
            @Size(max = 64) String unitName,
            List<@Valid DimensionRequirement> dimensionRequirements) {

        public AccountCreate(String code, String name, String category, String normalBalance) {
            this(code, name, category, normalBalance, null, false, null, false, null, List.of());
        }
    }

    public record AccountPatch(
            @NotNull Long expectedVersion,
            @Size(max = 32) String code,
            @Size(max = 200) String name,
            UUID parentId,
            @Pattern(regexp = "ASSET|LIABILITY|EQUITY|COST|REVENUE|EXPENSE") String category,
            @Pattern(regexp = "DEBIT|CREDIT") String normalBalance,
            @Pattern(regexp = "ACTIVE|INACTIVE") String status,
            Boolean cashFlowRequired,
            UUID defaultCashFlowItemId,
            Boolean quantityEnabled,
            @Size(max = 64) String unitName,
            List<@Valid DimensionRequirement> dimensionRequirements) {
    }

    public record DimensionRequirement(@NotNull UUID dimensionTypeId, boolean required) {
    }

    public record AccountCodeRuleUpdate(
            @NotBlank @Pattern(regexp = "[.-]") String separator,
            @NotNull Integer level2Width,
            @NotNull Integer level3Width,
            @NotNull Integer level4Width) {

        public AccountCodeRule toRule() {
            return new AccountCodeRule(separator, level2Width, level3Width, level4Width);
        }
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
