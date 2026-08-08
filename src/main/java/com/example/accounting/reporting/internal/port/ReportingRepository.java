package com.example.accounting.reporting.internal.port;

import com.example.accounting.reporting.ReportResponses;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReportingRepository {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalanceWithParents(UUID ledgerId, String periodCode);

    List<ReportResponses.LedgerLine> ledgerLines(UUID ledgerId, String periodCode);

    boolean periodExists(UUID ledgerId, String periodCode);

    boolean accountExists(UUID ledgerId, UUID accountId);

    ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID ledgerId, String periodCode, int page, int pageSize);

    ReportResponses.SubLedgerPage subLedgerBook(
            UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize);

    Set<String> formulaCategories(UUID ledgerId, String formulaCode, String field);

    String baseCurrency(UUID ledgerId);
}
