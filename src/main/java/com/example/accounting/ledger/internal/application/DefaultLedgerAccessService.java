package com.example.accounting.ledger.internal.application;

import com.example.accounting.administration.PlatformAdminPolicy;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.internal.port.LedgerAccessRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultLedgerAccessService implements LedgerAccessService {

    private final LedgerAccessRepository memberships;
    private final LocalSuperAgentPolicy localSuperAgent;
    private final PlatformAdminPolicy platformAdmin;

    public DefaultLedgerAccessService(
            LedgerAccessRepository memberships, LocalSuperAgentPolicy localSuperAgent,
            PlatformAdminPolicy platformAdmin) {
        this.memberships = memberships;
        this.localSuperAgent = localSuperAgent;
        this.platformAdmin = platformAdmin;
    }

    @Override
    @Transactional(readOnly = true)
    public LedgerRole requireMembership(UUID actorId, UUID ledgerId) {
        if (platformAdmin.isPlatformAdmin(actorId)) {
            if (!memberships.activeLedgerExists(ledgerId)) {
                throw new ApiProblemException(404, "LEDGER_NOT_FOUND", "Ledger not found",
                        "The ledger is not available to this user", false);
            }
            return LedgerRole.OWNER;
        }
        LedgerRole storedRole = memberships.findRole(actorId, ledgerId).orElseThrow(() ->
                new ApiProblemException(404, "LEDGER_NOT_FOUND", "Ledger not found",
                        "The ledger is not available to this user", false));
        return localSuperAgent.effectiveRole(actorId, storedRole);
    }
}
