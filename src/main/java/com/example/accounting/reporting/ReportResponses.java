package com.example.accounting.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

public final class ReportResponses {

    private ReportResponses() {
    }

    public record TrialBalanceLine(
            UUID accountId, String code, String name, String category,
            BigDecimal openingDebit, BigDecimal openingCredit,
            BigDecimal periodDebit, BigDecimal periodCredit,
            BigDecimal closingDebit, BigDecimal closingCredit,
            BigDecimal debit, BigDecimal credit, BigDecimal balance) {

        public TrialBalanceLine(UUID accountId, String code, String name, String category,
                                BigDecimal debit, BigDecimal credit, BigDecimal balance) {
            this(accountId, code, name, category,
                    BigDecimal.ZERO, BigDecimal.ZERO, debit, credit,
                    balance, BigDecimal.ZERO,
                    debit, credit, balance);
        }
    }

    public record StatementLine(String code, String name, BigDecimal amount) {
    }

    public record Statement(int totalLines, List<StatementLine> lines) {
    }

    public record LedgerLine(UUID voucherId, String voucherNumber, LocalDate voucherDate, String accountCode,
                             String accountName, String side, BigDecimal amount, String dimensionKey) {
    }

    public record Pagination(int page, int pageSize, long totalItems, int totalPages) {
    }

    public record GeneralLedgerAccount(
            UUID accountId, String accountCode, String accountName, String normalBalance,
            String openingDirection, BigDecimal openingBalance,
            BigDecimal periodDebit, BigDecimal periodCredit,
            BigDecimal yearDebit, BigDecimal yearCredit,
            String endingDirection, BigDecimal endingBalance) {
    }

    public record GeneralLedgerPage(
            String periodFrom, String periodTo, String periodCode,
            List<GeneralLedgerAccount> data, Pagination pagination) {

        public GeneralLedgerPage(String periodCode, List<GeneralLedgerAccount> data, Pagination pagination) {
            this(periodCode, periodCode, periodCode, data, pagination);
        }
    }

    public record SubLedgerEntry(
            UUID voucherId, String voucherNumber, LocalDate voucherDate,
            UUID postingAccountId, String postingAccountCode, String postingAccountName, String summary,
            BigDecimal debit, BigDecimal credit, String direction, BigDecimal balance) {
    }

    public record SubLedgerPage(
            String periodFrom, String periodTo, String periodCode,
            UUID accountId, String accountCode, String accountName,
            String openingDirection, BigDecimal openingBalance,
            List<SubLedgerEntry> data, BigDecimal periodDebit, BigDecimal periodCredit,
            String endingDirection, BigDecimal endingBalance, Pagination pagination) {

        public SubLedgerPage(
                String periodCode, UUID accountId, String accountCode, String accountName,
                String openingDirection, BigDecimal openingBalance,
                List<SubLedgerEntry> data, BigDecimal periodDebit, BigDecimal periodCredit,
                String endingDirection, BigDecimal endingBalance, Pagination pagination) {
            this(periodCode, periodCode, periodCode, accountId, accountCode, accountName,
                    openingDirection, openingBalance, data, periodDebit, periodCredit,
                    endingDirection, endingBalance, pagination);
        }
    }

    public record FinanceQueryLine(String groupKey, BigDecimal amount, String dimensionKey,
                                   List<FinanceQueryDimension> dimensions, String currency,
                                   String periodCode, String accountCode) {

        public FinanceQueryLine {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        public FinanceQueryLine(String groupKey, BigDecimal amount) {
            this(groupKey, amount, null, List.of(), null, null, null);
        }
    }

    public record FinanceQueryDimension(UUID dimensionTypeId, UUID dimensionValueId,
                                        String dimensionTypeCode, String dimensionTypeName,
                                        String dimensionValueCode, String dimensionValueName) {
    }

    public record DimensionLedgerPage(String projectionStatus, List<String> warnings,
                                      List<DimensionLedgerBalance> balances,
                                      List<DimensionLedgerEntry> entries, Pagination pagination) {
        public DimensionLedgerPage {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            balances = balances == null ? List.of() : List.copyOf(balances);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    public record DimensionLedgerBalance(UUID combinationId, String dimensionKey, String combinationKind,
                                         String groupKey, String currency,
                                         List<FinanceQueryDimension> dimensions,
                                         DimensionLedgerAmounts original,
                                         DimensionLedgerAmounts base) {
        public DimensionLedgerBalance {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    public record DimensionLedgerAmounts(BigDecimal openingDebit, BigDecimal openingCredit,
                                         BigDecimal periodDebit, BigDecimal periodCredit,
                                         BigDecimal closingDebit, BigDecimal closingCredit) {
    }

    public record DimensionLedgerEntry(UUID voucherId, String voucherNumber, LocalDate voucherDate,
                                      int lineNo, UUID lineId, UUID accountId, String accountCode,
                                      String accountName, UUID combinationId, String dimensionKey,
                                      String combinationKind, String groupKey,
                                      List<FinanceQueryDimension> dimensions,
                                      String currency, String side,
                                      BigDecimal originalDebit, BigDecimal originalCredit,
                                      BigDecimal baseDebit, BigDecimal baseCredit,
                                      BigDecimal runningOriginalDebit, BigDecimal runningOriginalCredit,
                                      BigDecimal runningBaseDebit, BigDecimal runningBaseCredit) {
        public DimensionLedgerEntry {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    public record LedgerProfile(String accountingStandardCode, String accountingStandardVersion,
                                String baseCurrency) {
    }
}
