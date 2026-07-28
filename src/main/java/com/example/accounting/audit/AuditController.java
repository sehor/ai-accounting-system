package com.example.accounting.audit;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
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
    public List<AuditResponses.Entry> list(HttpServletRequest request, @PathVariable UUID ledgerId) {
        return auditService.list(currentUserResolver.resolve(request), ledgerId);
    }
}
