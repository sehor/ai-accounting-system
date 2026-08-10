package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
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
                                                                HttpServletResponse response,
                                                                @PathVariable UUID ledgerId,
                                                                @RequestParam(required = false) String periodCode,
                                                                @RequestParam(required = false) String periodFrom,
                                                                @RequestParam(required = false) String periodTo,
                                                                @RequestParam(defaultValue = "false")
                                                                boolean includeParents) {
        PeriodRange range = PeriodRange.normalize(periodCode, periodFrom, periodTo);
        return respond(response, () -> reportingService.trialBalance(user(request), ledgerId, range, includeParents));
    }

    @GetMapping("/balance-sheet")
    public ReportResponses.Statement balanceSheet(HttpServletRequest request, HttpServletResponse response,
                                                  @PathVariable UUID ledgerId,
                                                  @RequestParam(required = false) String periodCode,
                                                  @RequestParam(required = false) String periodFrom,
                                                  @RequestParam(required = false) String periodTo) {
        PeriodRange range = PeriodRange.normalize(periodCode, periodFrom, periodTo);
        return respond(response, () -> reportingService.balanceSheet(user(request), ledgerId, range));
    }

    @GetMapping("/income-statement")
    public ReportResponses.Statement incomeStatement(HttpServletRequest request, HttpServletResponse response,
                                                     @PathVariable UUID ledgerId,
                                                     @RequestParam(required = false) String periodCode,
                                                     @RequestParam(required = false) String periodFrom,
                                                     @RequestParam(required = false) String periodTo) {
        PeriodRange range = PeriodRange.normalize(periodCode, periodFrom, periodTo);
        return respond(response, () -> reportingService.incomeStatement(user(request), ledgerId, range));
    }

    @GetMapping("/general-ledger")
    public List<ReportResponses.LedgerLine> generalLedger(HttpServletRequest request, HttpServletResponse response,
                                                          @PathVariable UUID ledgerId,
                                                          @RequestParam(required = false) String periodCode) {
        return respond(response, () -> reportingService.generalLedger(user(request), ledgerId, periodCode));
    }

    @GetMapping("/sub-ledger")
    public List<ReportResponses.LedgerLine> subLedger(HttpServletRequest request, HttpServletResponse response,
                                                      @PathVariable UUID ledgerId,
                                                      @RequestParam(required = false) String periodCode) {
        return respond(response, () -> reportingService.subLedger(user(request), ledgerId, periodCode));
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }

    private <T> T respond(HttpServletResponse response, Supplier<T> action) {
        try {
            T result = action.get();
            BalanceReadMetadata.Metadata metadata = BalanceReadMetadata.current();
            if (metadata == null) {
                metadata = new BalanceReadMetadata.Metadata("live-fallback", java.time.OffsetDateTime.now(), 0);
            }
            response.setHeader("X-Balance-Source", metadata.source());
            response.setHeader("X-Balance-As-Of", metadata.asOf().toString());
            response.setHeader("X-Balance-Lag-Ms", Long.toString(metadata.lagMs()));
            response.setHeader("Cache-Control", "no-store");
            return result;
        } finally {
            BalanceReadMetadata.clear();
        }
    }
}
