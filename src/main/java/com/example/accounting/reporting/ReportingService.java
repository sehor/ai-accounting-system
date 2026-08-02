package com.example.accounting.reporting;

import java.util.List;
import java.util.UUID;

public interface ReportingService {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, String periodCode, boolean includeParents);

    ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, String periodCode);

    ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.LedgerLine> generalLedger(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.LedgerLine> subLedger(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.FinanceQueryLine> financeQuery(UUID actorId, UUID ledgerId,
                                                        FinanceQueryRequests.Query request);
}
