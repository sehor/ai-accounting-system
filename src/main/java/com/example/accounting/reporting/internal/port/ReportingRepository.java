package com.example.accounting.reporting.internal.port;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.DimensionLedgerRequests;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.math.BigDecimal;

public interface ReportingRepository {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, PeriodRange range, boolean includeParents);

    List<ReportResponses.TrialBalanceLine> incomeStatementTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents);

    /**
     * Reads a statutory report source from the rolling balance projection only.  Unlike the
     * regular reporting path, this must never fall back to aggregating vouchers live.
     */
    boolean statutoryProjectionReady(UUID ledgerId, PeriodRange range);

    List<ReportResponses.TrialBalanceLine> statutoryTrialBalance(
            UUID ledgerId, PeriodRange range, boolean includeParents);

    List<ReportResponses.LedgerLine> ledgerLines(UUID ledgerId, String periodCode);

    boolean periodExists(UUID ledgerId, String periodCode);

    boolean periodsExist(UUID ledgerId, PeriodRange range);

    boolean accountExists(UUID ledgerId, UUID accountId);

    boolean leafAccount(UUID ledgerId, UUID accountId);

    ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID ledgerId, String periodCode, int page, int pageSize);

    ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID ledgerId, PeriodRange range, int page, int pageSize);

    ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize);

    ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, PeriodRange range, UUID accountId, int page, int pageSize);

    Set<String> formulaCategories(UUID ledgerId, String formulaCode, String field);

    String baseCurrency(UUID ledgerId);

    ReportResponses.LedgerProfile ledgerProfile(UUID ledgerId);

    String firstPeriodOfYear(UUID ledgerId, String periodCode);

    boolean dimensionProjectionReady(UUID ledgerId, PeriodRange range);

    boolean dimensionTypeExists(UUID ledgerId, UUID dimensionTypeId);

    DimensionTypeInfo dimensionType(UUID ledgerId, UUID dimensionTypeId);

    boolean dimensionValueExists(UUID ledgerId, UUID dimensionTypeId, UUID dimensionValueId);

    List<DimensionBalanceRow> dimensionBalances(UUID ledgerId, PeriodRange range,
                                                List<String> accountCodes, String currency,
                                                List<DimensionLedgerFilter> dimensionFilters,
                                                boolean closingPeriodOnly, int limit);

    List<DimensionLedgerBalanceRow> dimensionLedgerBalances(UUID ledgerId, PeriodRange range, UUID accountId,
                                                            String currency,
                                                            List<DimensionLedgerFilter> dimensionFilters);

    DimensionLedgerEntryPage dimensionLedgerEntries(UUID ledgerId, PeriodRange range, UUID accountId,
                                                    String currency,
                                                    List<DimensionLedgerFilter> dimensionFilters,
                                                    int page, int pageSize);

    record DimensionBalanceRow(String periodCode, UUID accountId, String accountCode, UUID combinationId,
                               String dimensionKey, String currency,
                               BigDecimal periodDebitBase, BigDecimal periodCreditBase,
                               BigDecimal closingDebitBase, BigDecimal closingCreditBase,
                               List<ReportResponses.FinanceQueryDimension> dimensions) {

        public DimensionBalanceRow {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    record DimensionTypeInfo(UUID id, String code, String name) {
    }

    record DimensionLedgerFilter(UUID dimensionTypeId, UUID dimensionValueId) {
        public static DimensionLedgerFilter from(DimensionLedgerRequests.DimensionValue value) {
            return new DimensionLedgerFilter(value.dimensionTypeId(), value.dimensionValueId());
        }
    }

    record DimensionLedgerBalanceRow(UUID combinationId, String dimensionKey, String combinationKind,
                                     String currency, BigDecimal openingDebitOriginal,
                                     BigDecimal openingCreditOriginal, BigDecimal periodDebitOriginal,
                                     BigDecimal periodCreditOriginal, BigDecimal closingDebitOriginal,
                                     BigDecimal closingCreditOriginal, BigDecimal openingDebitBase,
                                     BigDecimal openingCreditBase, BigDecimal periodDebitBase,
                                     BigDecimal periodCreditBase, BigDecimal closingDebitBase,
                                     BigDecimal closingCreditBase,
                                     List<ReportResponses.FinanceQueryDimension> dimensions) {
        public DimensionLedgerBalanceRow {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    record DimensionLedgerEntryRow(UUID voucherId, String voucherNumber, java.time.LocalDate voucherDate,
                                   int lineNo, UUID lineId, UUID accountId, String accountCode,
                                   String accountName, UUID combinationId, String dimensionKey,
                                   String combinationKind, String currency, String side,
                                   BigDecimal originalDebit, BigDecimal originalCredit,
                                   BigDecimal baseDebit, BigDecimal baseCredit,
                                   BigDecimal runningOriginalDebit, BigDecimal runningOriginalCredit,
                                   BigDecimal runningBaseDebit, BigDecimal runningBaseCredit,
                                   List<ReportResponses.FinanceQueryDimension> dimensions) {
        public DimensionLedgerEntryRow {
            dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        }
    }

    record DimensionLedgerEntryPage(List<DimensionLedgerEntryRow> entries, long totalItems) {
        public DimensionLedgerEntryPage {
            entries = entries == null ? List.of() : List.copyOf(entries);
        }
    }
}
