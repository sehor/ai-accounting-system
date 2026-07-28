package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/reports")
public class ReportController {

    private final CurrentUserResolver currentUserResolver;
    private final ReportingService reportingService;

    public ReportController(CurrentUserResolver currentUserResolver, ReportingService reportingService) {
        this.currentUserResolver = currentUserResolver;
        this.reportingService = reportingService;
    }

    @GetMapping("/trial-balance")
    public List<ReportResponses.TrialBalanceLine> trialBalance(HttpServletRequest request,
                                                                @PathVariable UUID ledgerId,
                                                                @RequestParam(required = false) String periodCode) {
        return reportingService.trialBalance(user(request), ledgerId, periodCode);
    }

    @GetMapping("/balance-sheet")
    public ReportResponses.Statement balanceSheet(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                  @RequestParam(required = false) String periodCode) {
        return reportingService.balanceSheet(user(request), ledgerId, periodCode);
    }

    @GetMapping("/income-statement")
    public ReportResponses.Statement incomeStatement(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                     @RequestParam(required = false) String periodCode) {
        return reportingService.incomeStatement(user(request), ledgerId, periodCode);
    }

    @GetMapping("/general-ledger")
    public List<ReportResponses.LedgerLine> generalLedger(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                          @RequestParam(required = false) String periodCode) {
        return reportingService.generalLedger(user(request), ledgerId, periodCode);
    }

    @GetMapping("/sub-ledger")
    public List<ReportResponses.LedgerLine> subLedger(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                      @RequestParam(required = false) String periodCode) {
        return reportingService.subLedger(user(request), ledgerId, periodCode);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
