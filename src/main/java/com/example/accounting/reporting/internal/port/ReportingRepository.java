package com.example.accounting.reporting.internal.port;

import com.example.accounting.reporting.ReportResponses;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public interface ReportingRepository {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID ledgerId, String periodCode);

    List<ReportResponses.LedgerLine> ledgerLines(UUID ledgerId, String periodCode);

    Set<String> formulaCategories(UUID ledgerId, String formulaCode, String field);

    String baseCurrency(UUID ledgerId);
}
