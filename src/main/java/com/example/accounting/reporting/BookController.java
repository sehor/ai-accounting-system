package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/books")
public class BookController {

    private final CurrentUserResolver currentUserResolver;
    private final ReportingService reportingService;

    public BookController(CurrentUserResolver currentUserResolver, ReportingService reportingService) {
        this.currentUserResolver = currentUserResolver;
        this.reportingService = reportingService;
    }

    @GetMapping("/general-ledger")
    public ReportResponses.GeneralLedgerPage generalLedger(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam String periodCode,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return reportingService.generalLedgerBook(
                currentUserResolver.resolve(request), ledgerId, periodCode, page, pageSize);
    }

    @GetMapping("/sub-ledger")
    public ReportResponses.SubLedgerPage subLedger(
            HttpServletRequest request, @PathVariable UUID ledgerId,
            @RequestParam String periodCode, @RequestParam UUID accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        return reportingService.subLedgerBook(
                currentUserResolver.resolve(request), ledgerId, periodCode, accountId, page, pageSize);
    }
}
