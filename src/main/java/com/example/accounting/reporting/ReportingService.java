package com.example.accounting.reporting;

import java.util.List;
import java.util.UUID;

public interface ReportingService {

    List<ReportResponses.TrialBalanceLine> trialBalance(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, String periodCode, boolean includeParents);

    List<ReportResponses.TrialBalanceLine> trialBalance(
            UUID actorId, UUID ledgerId, PeriodRange range, boolean includeParents);

    ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, String periodCode);

    ReportResponses.Statement balanceSheet(UUID actorId, UUID ledgerId, PeriodRange range);

    ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, String periodCode);

    ReportResponses.Statement incomeStatement(UUID actorId, UUID ledgerId, PeriodRange range);

    StatutoryReportResponses.Statement statutoryStatement(UUID actorId, UUID ledgerId,
                                                          String reportType, String periodCode);

    List<ReportResponses.LedgerLine> generalLedger(UUID actorId, UUID ledgerId, String periodCode);

    List<ReportResponses.LedgerLine> subLedger(UUID actorId, UUID ledgerId, String periodCode);

    ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID actorId, UUID ledgerId, String periodCode, int page, int pageSize);

    ReportResponses.GeneralLedgerPage generalLedgerBook(
            UUID actorId, UUID ledgerId, PeriodRange range, int page, int pageSize);

    ReportResponses.SubLedgerPage subLedgerBook(
            UUID actorId, UUID ledgerId, String periodCode, UUID accountId, int page, int pageSize);

    ReportResponses.SubLedgerPage subLedgerBook(
            UUID actorId, UUID ledgerId, PeriodRange range, UUID accountId, int page, int pageSize);

    List<ReportResponses.FinanceQueryLine> financeQuery(UUID actorId, UUID ledgerId,
                                                        FinanceQueryRequests.Query request);

    ReportResponses.DimensionLedgerPage dimensionLedger(UUID actorId, UUID ledgerId,
                                                         DimensionLedgerRequests.Query request);
}
