package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.time.LocalDate;

public final class ReportResponses {

    private ReportResponses() {
    }

    @Schema(requiredProperties = {"accountId", "code", "name", "category", "openingDebit",
            "openingCredit", "periodDebit", "periodCredit", "closingDebit", "closingCredit",
            "debit", "credit", "balance"})
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

    @Schema(requiredProperties = {"code", "name", "amount"})
    public record StatementLine(String code, String name, BigDecimal amount) {
    }

    @Schema(name = "AccountStatement", requiredProperties = {"totalLines", "lines"})
    public record Statement(int totalLines, List<StatementLine> lines) {
    }

    @Schema(requiredProperties = {"voucherId", "voucherNumber", "voucherDate", "accountCode",
            "accountName", "side", "amount", "dimensionKey"})
    public record LedgerLine(UUID voucherId, String voucherNumber, LocalDate voucherDate, String accountCode,
                             String accountName, String side, BigDecimal amount,
                             @Schema(nullable = true) String dimensionKey) {
    }

    @Schema(requiredProperties = {"page", "pageSize", "totalItems", "totalPages"})
    public record Pagination(int page, int pageSize, long totalItems, int totalPages) {
    }

    @Schema(requiredProperties = {"accountId", "accountCode", "accountName", "normalBalance",
            "openingDirection", "openingBalance", "periodDebit", "periodCredit", "yearDebit",
            "yearCredit", "endingDirection", "endingBalance"})
    public record GeneralLedgerAccount(
            UUID accountId, String accountCode, String accountName, String normalBalance,
            String openingDirection, BigDecimal openingBalance,
            BigDecimal periodDebit, BigDecimal periodCredit,
            BigDecimal yearDebit, BigDecimal yearCredit,
            String endingDirection, BigDecimal endingBalance) {
    }

    @Schema(requiredProperties = {"periodFrom", "periodTo", "periodCode", "data", "pagination"})
    public record GeneralLedgerPage(
            String periodFrom, String periodTo, String periodCode,
            List<GeneralLedgerAccount> data, Pagination pagination) {

        public GeneralLedgerPage(String periodCode, List<GeneralLedgerAccount> data, Pagination pagination) {
            this(periodCode, periodCode, periodCode, data, pagination);
        }
    }

    @Schema(requiredProperties = {"voucherId", "voucherNumber", "voucherDate", "postingAccountId",
            "postingAccountCode", "postingAccountName", "summary", "debit", "credit", "direction", "balance"})
    public record SubLedgerEntry(
            UUID voucherId, String voucherNumber, LocalDate voucherDate,
            UUID postingAccountId, String postingAccountCode, String postingAccountName,
            @Schema(nullable = true) String summary,
            BigDecimal debit, BigDecimal credit, String direction, BigDecimal balance) {
    }

    @Schema(requiredProperties = {"periodFrom", "periodTo", "periodCode", "accountId", "accountCode",
            "accountName", "openingDirection", "openingBalance", "data", "periodDebit", "periodCredit",
            "endingDirection", "endingBalance", "pagination"})
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

    @Schema(requiredProperties = {"groupKey", "amount", "dimensionKey", "dimensions", "currency",
            "periodCode", "accountCode"})
    public record FinanceQueryLine(@Schema(nullable = true) String groupKey, BigDecimal amount,
                                   @Schema(nullable = true) String dimensionKey,
                                   List<FinanceQueryDimension> dimensions,
                                   @Schema(nullable = true) String currency,
                                   @Schema(nullable = true) String periodCode,
                                   @Schema(nullable = true) String accountCode) {

        public FinanceQueryLine {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }

        public FinanceQueryLine(String groupKey, BigDecimal amount) {
            this(groupKey, amount, null, List.of(), null, null, null);
        }
    }

    @Schema(requiredProperties = {"dimensionTypeId", "dimensionValueId", "dimensionTypeCode",
            "dimensionTypeName", "dimensionValueCode", "dimensionValueName"})
    public record FinanceQueryDimension(UUID dimensionTypeId, UUID dimensionValueId,
                                        String dimensionTypeCode, String dimensionTypeName,
                                        String dimensionValueCode, String dimensionValueName) {
    }

    @Schema(requiredProperties = {"projectionStatus", "warnings", "balances", "entries", "pagination"})
    public record DimensionLedgerPage(String projectionStatus, List<String> warnings,
                                      List<DimensionLedgerBalance> balances,
                                      List<DimensionLedgerEntry> entries, Pagination pagination) {
        public DimensionLedgerPage {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            balances = balances == null ? List.of() : List.copyOf(balances);
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }

    @Schema(requiredProperties = {"combinationId", "dimensionKey", "combinationKind", "groupKey",
            "currency", "dimensions", "original", "base"})
    public record DimensionLedgerBalance(UUID combinationId, String dimensionKey, String combinationKind,
                                         @Schema(nullable = true) String groupKey, String currency,
                                         List<FinanceQueryDimension> dimensions,
                                         DimensionLedgerAmounts original,
                                         DimensionLedgerAmounts base) {
        public DimensionLedgerBalance {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    @Schema(requiredProperties = {"openingDebit", "openingCredit", "periodDebit", "periodCredit",
            "closingDebit", "closingCredit"})
    public record DimensionLedgerAmounts(BigDecimal openingDebit, BigDecimal openingCredit,
                                         BigDecimal periodDebit, BigDecimal periodCredit,
                                         BigDecimal closingDebit, BigDecimal closingCredit) {
    }

    @Schema(requiredProperties = {"voucherId", "voucherNumber", "voucherDate", "lineNo", "lineId",
            "accountId", "accountCode", "accountName", "combinationId", "dimensionKey", "combinationKind",
            "groupKey", "dimensions", "currency", "side", "originalDebit", "originalCredit", "baseDebit",
            "baseCredit", "runningOriginalDebit", "runningOriginalCredit", "runningBaseDebit",
            "runningBaseCredit"})
    public record DimensionLedgerEntry(UUID voucherId, String voucherNumber, LocalDate voucherDate,
                                      int lineNo, UUID lineId, UUID accountId, String accountCode,
                                      String accountName, UUID combinationId, String dimensionKey,
                                      String combinationKind, @Schema(nullable = true) String groupKey,
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

    @Schema(requiredProperties = {"accountingStandardCode", "accountingStandardVersion", "baseCurrency"})
    public record LedgerProfile(String accountingStandardCode, String accountingStandardVersion,
                                String baseCurrency) {
    }
}
