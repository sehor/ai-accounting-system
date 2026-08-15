package com.example.accounting.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class LedgerResponses {

    private LedgerResponses() {
    }

    @Schema(name = "LedgerResponse", requiredProperties = {"id", "name", "description",
            "accountingStandardCode", "accountingStandardVersion", "baseCurrency", "startDate",
            "approvalEnabled", "status"})
    public record Ledger(UUID id, String name, String description,
                         String accountingStandardCode, String accountingStandardVersion,
                         String baseCurrency, LocalDate startDate, boolean approvalEnabled, String status) {

        public Ledger(UUID id, String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, boolean approvalEnabled, String status) {
            this(id, name, "", accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, status);
        }
    }

    @Schema(requiredProperties = {"userId", "role", "status", "displayName", "email"})
    public record Member(UUID userId, LedgerRole role, MembershipStatus status,
                         String displayName, @Schema(nullable = true) String email) {
    }

    @Schema(requiredProperties = {"id", "ledgerId", "code", "name", "category", "normalBalance",
            "status", "level", "isLeaf", "isTemplate", "hasBusinessUsage", "coreLocked", "legacyCode",
            "version", "cashFlowRequired", "quantityEnabled", "dimensionRequirements", "createdAt",
            "standardAccountKey", "parentId", "defaultCashFlowItemId", "unitName"})
    public record Account(
            UUID id,
            UUID ledgerId,
            String code,
            String name,
            @Schema(nullable = true) String standardAccountKey,
            String category,
            String normalBalance,
            String status,
            @Schema(nullable = true) UUID parentId,
            int level,
            boolean isLeaf,
            boolean isTemplate,
            boolean hasBusinessUsage,
            boolean coreLocked,
            boolean legacyCode,
            long version,
            boolean cashFlowRequired,
            @Schema(nullable = true) UUID defaultCashFlowItemId,
            boolean quantityEnabled,
            @Schema(nullable = true) String unitName,
            List<DimensionRequirement> dimensionRequirements,
            @Schema(nullable = true) OffsetDateTime createdAt) {

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status) {
            this(id, ledgerId, code, name, null, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of(), null);
        }

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status, OffsetDateTime createdAt) {
            this(id, ledgerId, code, name, null, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of(), createdAt);
        }

        public Account(UUID id, UUID ledgerId, String code, String name, String standardAccountKey,
                       String category, String normalBalance, String status, OffsetDateTime createdAt) {
            this(id, ledgerId, code, name, standardAccountKey, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of(), createdAt);
        }

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status, UUID parentId, int level,
                       boolean isLeaf, boolean isTemplate, boolean hasBusinessUsage,
                       boolean coreLocked, boolean legacyCode, long version,
                       boolean cashFlowRequired, UUID defaultCashFlowItemId,
                       boolean quantityEnabled, String unitName,
                       List<DimensionRequirement> dimensionRequirements, OffsetDateTime createdAt) {
            this(id, ledgerId, code, name, null, category, normalBalance, status, parentId, level,
                    isLeaf, isTemplate, hasBusinessUsage, coreLocked, legacyCode, version,
                    cashFlowRequired, defaultCashFlowItemId, quantityEnabled, unitName,
                    dimensionRequirements, createdAt);
        }
    }

    @Schema(name = "LedgerAccountSummary", requiredProperties = {"id", "code", "name", "status"})
    public record AccountSummary(UUID id, String code, String name, String status) {
    }

    @Schema(name = "LedgerAccountSearchResult", requiredProperties = {"account", "parent", "children"})
    public record AccountSearchResult(
            Account account,
            @Schema(nullable = true) AccountSummary parent,
            List<AccountSummary> children) {
    }

    @Schema(name = "AccountDimensionRequirementResponse",
            requiredProperties = {"dimensionTypeId", "code", "name", "required"})
    public record DimensionRequirement(UUID dimensionTypeId, String code, String name, boolean required) {
    }

    @Schema(name = "LedgerCashFlowItem",
            requiredProperties = {"id", "ledgerId", "code", "name", "status", "template"})
    public record CashFlowItem(UUID id, UUID ledgerId, String code, String name, String status,
                               boolean template) {
    }

    @Schema(requiredProperties = {"id", "ledgerId", "periodCode", "startDate", "endDate", "status",
            "hasVouchers"})
    public record Period(UUID id, UUID ledgerId, String periodCode, LocalDate startDate,
                         LocalDate endDate, String status, boolean hasVouchers) {

        public Period(UUID id, UUID ledgerId, String periodCode, LocalDate startDate,
                      LocalDate endDate, String status) {
            this(id, ledgerId, periodCode, startDate, endDate, status, false);
        }
    }

    @Schema(requiredProperties = {"id", "ledgerId", "code", "name", "required", "status", "version"})
    public record DimensionType(UUID id, UUID ledgerId, String code, String name, boolean required, String status,
                                long version) {

        public DimensionType(UUID id, UUID ledgerId, String code, String name, boolean required, String status) {
            this(id, ledgerId, code, name, required, status, 0);
        }
    }

    @Schema(name = "LedgerDimensionValue",
            requiredProperties = {"id", "ledgerId", "dimensionTypeId", "code", "name", "status", "version"})
    public record DimensionValue(UUID id, UUID ledgerId, UUID dimensionTypeId, String code, String name,
                                 String status, long version) {

        public DimensionValue(UUID id, UUID ledgerId, UUID dimensionTypeId, String code, String name,
                              String status) {
            this(id, ledgerId, dimensionTypeId, code, name, status, 0);
        }
    }

    @Schema(name = "DimensionValueGroup", requiredProperties = {"dimensionTypeId", "values"})
    public record DimensionValueGroup(UUID dimensionTypeId, List<DimensionValue> values) {
    }

    @Schema(name = "DimensionValuesBatchResponse", requiredProperties = {"groups"})
    public record DimensionValuesBatch(List<DimensionValueGroup> groups) {
    }

    @Schema(name = "OpeningBalanceResponse", requiredProperties = {
            "id", "ledgerId", "periodId", "accountId", "currency", "dimensionKey", "debitOriginal",
            "creditOriginal", "exchangeRate", "debitBase", "creditBase", "confirmed", "dimensions"})
    public record OpeningBalance(UUID id, UUID ledgerId, UUID periodId, UUID accountId, String currency,
                                 @Schema(nullable = true) String dimensionKey,
                                 BigDecimal debitOriginal, BigDecimal creditOriginal,
                                 BigDecimal exchangeRate, BigDecimal debitBase, BigDecimal creditBase,
                                 boolean confirmed, List<OpeningBalanceDimension> dimensions) {

        public OpeningBalance {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        public OpeningBalance(UUID id, UUID ledgerId, UUID periodId, UUID accountId, String currency,
                              String dimensionKey, BigDecimal debitOriginal, BigDecimal creditOriginal,
                              BigDecimal exchangeRate, BigDecimal debitBase, BigDecimal creditBase,
                              boolean confirmed) {
            this(id, ledgerId, periodId, accountId, currency, dimensionKey, debitOriginal, creditOriginal,
                    exchangeRate, debitBase, creditBase, confirmed, List.of());
        }
    }

    @Schema(name = "OpeningBalanceDimensionResponse", requiredProperties = {
            "dimensionTypeId", "dimensionValueId", "dimensionTypeCode", "dimensionTypeName",
            "dimensionValueCode", "dimensionValueName"})
    public record OpeningBalanceDimension(UUID dimensionTypeId, UUID dimensionValueId,
                                          String dimensionTypeCode, String dimensionTypeName,
                                          String dimensionValueCode, String dimensionValueName) {
    }
}
