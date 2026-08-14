package com.example.accounting.ledger;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class LedgerResponses {

    private LedgerResponses() {
    }

    public record Ledger(UUID id, String name, String description,
                         String accountingStandardCode, String accountingStandardVersion,
                         String baseCurrency, LocalDate startDate, boolean approvalEnabled, String status) {

        public Ledger(UUID id, String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, boolean approvalEnabled, String status) {
            this(id, name, "", accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, status);
        }
    }

    public record Member(UUID userId, LedgerRole role, MembershipStatus status,
                         String displayName, String email) {
    }

    public record Account(
            UUID id,
            UUID ledgerId,
            String code,
            String name,
            String category,
            String normalBalance,
            String status,
            UUID parentId,
            int level,
            boolean isLeaf,
            boolean isTemplate,
            boolean hasBusinessUsage,
            boolean coreLocked,
            boolean legacyCode,
            long version,
            boolean cashFlowRequired,
            UUID defaultCashFlowItemId,
            boolean quantityEnabled,
            String unitName,
            List<DimensionRequirement> dimensionRequirements,
            OffsetDateTime createdAt) {

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status) {
            this(id, ledgerId, code, name, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of(), null);
        }

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status, OffsetDateTime createdAt) {
            this(id, ledgerId, code, name, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of(), createdAt);
        }
    }

    public record AccountSummary(UUID id, String code, String name, String status) {
    }

    public record AccountSearchResult(
            Account account,
            AccountSummary parent,
            List<AccountSummary> children) {
    }

    public record DimensionRequirement(UUID dimensionTypeId, String code, String name, boolean required) {
    }

    public record CashFlowItem(UUID id, UUID ledgerId, String code, String name, String status,
                               boolean template) {
    }

    public record Period(UUID id, UUID ledgerId, String periodCode, LocalDate startDate,
                         LocalDate endDate, String status, boolean hasVouchers) {

        public Period(UUID id, UUID ledgerId, String periodCode, LocalDate startDate,
                      LocalDate endDate, String status) {
            this(id, ledgerId, periodCode, startDate, endDate, status, false);
        }
    }

    public record DimensionType(UUID id, UUID ledgerId, String code, String name, boolean required, String status,
                                long version) {

        public DimensionType(UUID id, UUID ledgerId, String code, String name, boolean required, String status) {
            this(id, ledgerId, code, name, required, status, 0);
        }
    }

    public record DimensionValue(UUID id, UUID ledgerId, UUID dimensionTypeId, String code, String name,
                                 String status, long version) {

        public DimensionValue(UUID id, UUID ledgerId, UUID dimensionTypeId, String code, String name,
                              String status) {
            this(id, ledgerId, dimensionTypeId, code, name, status, 0);
        }
    }

    public record OpeningBalance(UUID id, UUID ledgerId, UUID periodId, UUID accountId, String currency,
                                 String dimensionKey, BigDecimal debitOriginal, BigDecimal creditOriginal,
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

    public record OpeningBalanceDimension(UUID dimensionTypeId, UUID dimensionValueId,
                                          String dimensionTypeCode, String dimensionTypeName,
                                          String dimensionValueCode, String dimensionValueName) {
    }
}
