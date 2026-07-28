package com.example.accounting.ledger;

import java.time.LocalDate;
import java.math.BigDecimal;
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

    public record Account(UUID id, UUID ledgerId, String code, String name, String category,
                          String normalBalance, String status) {
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
