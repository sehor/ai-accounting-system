package com.example.accounting.reporting;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
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
    public List<ReportResponses.FinanceQueryLine> query(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                        @Valid @RequestBody FinanceQueryRequests.Query body) {
        return reportingService.financeQuery(currentUserResolver.resolve(request), ledgerId, body);
    }
}
