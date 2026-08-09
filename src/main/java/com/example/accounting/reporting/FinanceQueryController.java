package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}")
public class FinanceQueryController {

    private final CurrentUserResolver currentUserResolver;
    private final ReportingService reportingService;

    public FinanceQueryController(CurrentUserResolver currentUserResolver, ReportingService reportingService) {
        this.currentUserResolver = currentUserResolver;
        this.reportingService = reportingService;
    }

    @PostMapping("/finance-query")
    public List<ReportResponses.FinanceQueryLine> query(HttpServletRequest request, HttpServletResponse response,
                                                        @PathVariable UUID ledgerId,
                                                        @Valid @RequestBody FinanceQueryRequests.Query body) {
        return respond(response, () -> reportingService.financeQuery(currentUserResolver.resolve(request), ledgerId, body));
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
