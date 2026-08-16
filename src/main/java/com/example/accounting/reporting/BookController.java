package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
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
            HttpServletRequest request, HttpServletResponse response, @PathVariable UUID ledgerId,
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PeriodRange range = PeriodRange.normalize(periodCode, periodFrom, periodTo);
        return respond(response, () -> range.singlePeriod()
                ? reportingService.generalLedgerBook(
                        currentUserResolver.resolve(request), ledgerId, range.periodCode(), page, pageSize)
                : reportingService.generalLedgerBook(
                        currentUserResolver.resolve(request), ledgerId, range, page, pageSize));
    }

    @GetMapping("/sub-ledger")
    public ReportResponses.SubLedgerPage subLedger(
            HttpServletRequest request, HttpServletResponse response, @PathVariable UUID ledgerId,
            @RequestParam(required = false) String periodCode,
            @RequestParam(required = false) String periodFrom,
            @RequestParam(required = false) String periodTo,
            @RequestParam UUID accountId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "50") int pageSize) {
        PeriodRange range = PeriodRange.normalize(periodCode, periodFrom, periodTo);
        return respond(response, () -> range.singlePeriod()
                ? reportingService.subLedgerBook(
                        currentUserResolver.resolve(request), ledgerId, range.periodCode(), accountId, page, pageSize)
                : reportingService.subLedgerBook(
                        currentUserResolver.resolve(request), ledgerId, range, accountId, page, pageSize));
    }

    @PostMapping("/dimension-ledger:query")
    public ReportResponses.DimensionLedgerPage dimensionLedger(HttpServletRequest request,
                                                               HttpServletResponse response,
                                                               @PathVariable UUID ledgerId,
                                                               @Valid @RequestBody DimensionLedgerRequests.Query body) {
        return respond(response, () -> reportingService.dimensionLedger(
                currentUserResolver.resolve(request), ledgerId, body));
    }

    private <T> T respond(HttpServletResponse response, Supplier<T> action) {
        try {
            T result = action.get();
            BalanceReadMetadata.Metadata metadata = BalanceReadMetadata.current();
            if (metadata == null) {
                metadata = new BalanceReadMetadata.Metadata(
                        "live-fallback", java.time.OffsetDateTime.now(), 0);
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
