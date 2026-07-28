package com.example.accounting.audit.internal.application;

import com.example.accounting.audit.AuditResponses;
import com.example.accounting.audit.AuditService;
import com.example.accounting.audit.internal.port.AuditRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAuditService implements AuditService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final LedgerAccessService ledgerAccess;
    private final AuditRepository audits;

    public DefaultAuditService(LedgerAccessService ledgerAccess, AuditRepository audits) {
        this.ledgerAccess = ledgerAccess;
        this.audits = audits;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditResponses.Entry> list(UUID actorId, UUID ledgerId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw new ApiProblemException(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view audit records", false);
        }
        return audits.list(ledgerId);
    }
}
