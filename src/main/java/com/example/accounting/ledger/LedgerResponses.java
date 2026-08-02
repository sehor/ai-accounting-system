package com.example.accounting.ledger;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public final class LedgerResponses {

    private LedgerResponses() {
    }

    public record Ledger(UUID id, String name, String accountingStandardCode, String accountingStandardVersion,
                         String baseCurrency, LocalDate startDate, boolean approvalEnabled, String status) {
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
            List<DimensionRequirement> dimensionRequirements) {

        public Account(UUID id, UUID ledgerId, String code, String name, String category,
                       String normalBalance, String status) {
            this(id, ledgerId, code, name, category, normalBalance, status, null, 1,
                    true, false, false, false, false, 0, false, null, false, null, List.of());
        }
    }

    public record DimensionRequirement(UUID dimensionTypeId, String code, String name, boolean required) {
    }

    public record CashFlowItem(UUID id, UUID ledgerId, String code, String name, String status,
                               boolean template) {
    }

    public record Period(UUID id, UUID ledgerId, String periodCode, LocalDate startDate,
                         LocalDate endDate, String status) {
    }

    public record DimensionType(UUID id, UUID ledgerId, String code, String name, boolean required, String status) {
    }

    public record DimensionValue(UUID id, UUID ledgerId, UUID dimensionTypeId, String code, String name, String status) {
    }

    public record OpeningBalance(UUID id, UUID ledgerId, UUID periodId, UUID accountId, String currency,
                                 String dimensionKey, BigDecimal debitOriginal, BigDecimal creditOriginal,
                                 BigDecimal exchangeRate, BigDecimal debitBase, BigDecimal creditBase,
                                 boolean confirmed) {
    }
}
