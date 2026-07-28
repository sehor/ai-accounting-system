package com.example.accounting.reporting;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

public final class ReportResponses {

    private ReportResponses() {
    }

    public record TrialBalanceLine(UUID accountId, String code, String name, String category,
                                   BigDecimal debit, BigDecimal credit, BigDecimal balance) {
    }

    public record StatementLine(String code, String name, BigDecimal amount) {
    }

    public record Statement(int totalLines, List<StatementLine> lines) {
    }

    public record LedgerLine(UUID voucherId, String voucherNumber, LocalDate voucherDate, String accountCode,
                             String accountName, String side, BigDecimal amount, String dimensionKey) {
    }

    public record FinanceQueryLine(String groupKey, BigDecimal amount) {
    }
}
