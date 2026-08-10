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
                    balance.signum() >= 0 ? balance : BigDecimal.ZERO,
                    balance.signum() < 0 ? balance.negate() : BigDecimal.ZERO,
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

    public record FinanceQueryLine(String groupKey, BigDecimal amount) {
    }
}
