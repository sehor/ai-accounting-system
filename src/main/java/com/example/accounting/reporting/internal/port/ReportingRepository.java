package com.example.accounting.reporting.internal.port;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.PeriodRange;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReportingRepository {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, PeriodRange range, boolean includeParents);

    List<ReportResponses.LedgerLine> ledgerLines(UUID ledgerId, String periodCode);

    boolean periodExists(UUID ledgerId, String periodCode);

    boolean periodsExist(UUID ledgerId, PeriodRange range);

    boolean accountExists(UUID ledgerId, UUID accountId);

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
}
