package com.example.accounting.audit;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/audit")
public class AuditController {

    private final CurrentUserResolver currentUserResolver;
    private final AuditService auditService;

    public AuditController(CurrentUserResolver currentUserResolver, AuditService auditService) {
        this.currentUserResolver = currentUserResolver;
        this.auditService = auditService;
    }

    @GetMapping
    public AuditResponses.Page list(HttpServletRequest request, @PathVariable UUID ledgerId,
                                    @RequestParam(defaultValue = "50") int limit,
                                    @RequestParam(required = false) String cursor,
                                    @RequestParam(required = false) String aggregateType,
                                    @RequestParam(required = false) UUID aggregateId) {
        return auditService.page(currentUserResolver.resolve(request), ledgerId, limit, cursor,
                aggregateType, aggregateId);
    }
}
