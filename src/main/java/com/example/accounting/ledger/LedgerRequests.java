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

    public enum AccountMatchMode {
        EXACT,
        FUZZY
    }

    public record Create(@NotBlank @Size(max = 200) String name,
                         @Size(max = 2000) String description,
                         @NotBlank String accountingStandardCode,
                         @NotBlank String accountingStandardVersion,
                         @NotBlank @Pattern(regexp = "[A-Z]{3}") String baseCurrency,
                         @NotNull LocalDate startDate,
                         Boolean approvalEnabled,
                         @Valid AccountCodeRule accountCodeRule) {

        public Create(String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, Boolean approvalEnabled) {
            this(name, null, accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, null);
        }

        public Create(String name, String description, String accountingStandardCode,
                      String accountingStandardVersion, String baseCurrency, LocalDate startDate,
                      Boolean approvalEnabled) {
            this(name, description, accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, null);
        }

        public Create(String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, Boolean approvalEnabled,
                      AccountCodeRule accountCodeRule) {
            this(name, null, accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, accountCodeRule);
        }
    }

    public record AddMember(@NotNull UUID userId, @NotNull LedgerRole role) {
    }

    public record Rename(@NotBlank @Size(max = 200) String name,
                         @Size(max = 2000) String description) {

        public Rename(String name) {
            this(name, null);
        }
    }

    public record UpdateMember(@NotNull LedgerRole role, @NotNull MembershipStatus status) {
    }

    public record AccountCreate(
            @NotBlank @Pattern(regexp = "[A-Za-z0-9._-]{1,32}") String code,
            @NotBlank @Size(max = 200) String name,
            @Pattern(regexp = "[A-Z][A-Z0-9]*(\\.[A-Z0-9_]+)+") String standardAccountKey,
            @NotBlank @Pattern(regexp = "CURRENT_ASSET|NON_CURRENT_ASSET|CURRENT_LIABILITY|NON_CURRENT_LIABILITY|EQUITY|COST|OPERATING_REVENUE|OTHER_INCOME|OPERATING_COST_AND_TAX|OTHER_EXPENSE|PERIOD_EXPENSE|INCOME_TAX|PRIOR_YEAR_ADJUSTMENT") String category,
            @NotBlank @Pattern(regexp = "DEBIT|CREDIT") String normalBalance,
            UUID parentId,
            Boolean cashFlowRequired,
            UUID defaultCashFlowItemId,
            Boolean quantityEnabled,
            @Size(max = 64) String unitName,
            List<@Valid DimensionRequirement> dimensionRequirements) {

        public AccountCreate(String code, String name, String category, String normalBalance) {
            this(code, name, null, category, normalBalance, null, false, null, false, null, List.of());
        }

        public AccountCreate(String code, String name, String standardAccountKey,
                             String category, String normalBalance) {
            this(code, name, standardAccountKey, category, normalBalance,
                    null, false, null, false, null, List.of());
        }

        public AccountCreate(String code, String name, String category, String normalBalance,
                             UUID parentId, Boolean cashFlowRequired, UUID defaultCashFlowItemId,
                             Boolean quantityEnabled, String unitName,
                             List<DimensionRequirement> dimensionRequirements) {
            this(code, name, null, category, normalBalance, parentId, cashFlowRequired,
                    defaultCashFlowItemId, quantityEnabled, unitName, dimensionRequirements);
        }
    }

    public record AccountPatch(
            @NotNull Long expectedVersion,
            @Size(max = 32) String code,
            @Size(max = 200) String name,
            UUID parentId,
            @Pattern(regexp = "CURRENT_ASSET|NON_CURRENT_ASSET|CURRENT_LIABILITY|NON_CURRENT_LIABILITY|EQUITY|COST|OPERATING_REVENUE|OTHER_INCOME|OPERATING_COST_AND_TAX|OTHER_EXPENSE|PERIOD_EXPENSE|INCOME_TAX|PRIOR_YEAR_ADJUSTMENT") String category,
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
            @NotNull Integer level2Width,
            @NotNull Integer level3Width,
            @NotNull Integer level4Width) {

        public AccountCodeRule toRule() {
            return new AccountCodeRule(level2Width, level3Width, level4Width);
        }
    }

    public record PeriodAction(@NotBlank String reason) {
    }

    public record DimensionTypeCreate(@NotBlank String code, @NotBlank String name, Boolean required) {
    }

    public record DimensionTypePatch(@NotNull Long expectedVersion,
                                     @Size(min = 1, max = 200) String name,
                                     @Pattern(regexp = "ACTIVE|INACTIVE") String status,
                                     Boolean required) {
    }

    public record DimensionValueCreate(@NotBlank String code, @NotBlank String name) {
    }

    public record DimensionValuePatch(@NotNull Long expectedVersion,
                                      @Size(min = 1, max = 200) String name,
                                      @Pattern(regexp = "ACTIVE|INACTIVE") String status) {
    }

    public record OpeningBalances(@NotEmpty List<@Valid OpeningBalanceLine> lines,
                                  @Size(max = 1000) String reason) {

        public OpeningBalances(List<OpeningBalanceLine> lines) {
            this(lines, null);
        }
    }

    public record OpeningBalanceLine(@NotNull UUID accountId,
                                     @NotNull UUID periodId,
                                     @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
                                     String dimensionKey,
                                     @NotNull BigDecimal debitOriginal,
                                     @NotNull BigDecimal creditOriginal,
                                     @NotNull BigDecimal exchangeRate,
                                     List<@Valid OpeningBalanceDimension> dimensions) {

        public OpeningBalanceLine {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        public OpeningBalanceLine(UUID accountId, UUID periodId, String currency, String dimensionKey,
                                  BigDecimal debitOriginal, BigDecimal creditOriginal,
                                  BigDecimal exchangeRate) {
            this(accountId, periodId, currency, dimensionKey, debitOriginal, creditOriginal,
                    exchangeRate, List.of());
        }
    }

    public record OpeningBalanceDimension(@NotNull UUID dimensionTypeId,
                                          @NotNull UUID dimensionValueId) {
    }
}
