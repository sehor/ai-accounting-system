package com.example.accounting.ledger.internal.application;

import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class LocalSuperAgentPolicy {

    private final boolean enabled;
    private final UUID userId;

    public LocalSuperAgentPolicy(
            @Value("${app.security.local-super-agent-enabled:false}") boolean enabled,
            @Value("${app.security.local-super-agent-user-id:00000000-0000-4000-8000-000000000099}") UUID userId) {
        this.enabled = enabled;
        this.userId = userId;
    }

    public LedgerRole effectiveRole(UUID actorId, LedgerRole storedRole) {
        return isSuperAgent(actorId) ? LedgerRole.OWNER : storedRole;
    }

    public void requireUserManagementAllowed(UUID actorId) {
        if (isSuperAgent(actorId)) {
            throw new ApiProblemException(403, "SUPER_AGENT_USER_MANAGEMENT_FORBIDDEN",
                    "User management is not available",
                    "The local super-agent cannot manage ledger members", false);
        }
    }

    private boolean isSuperAgent(UUID actorId) {
        return enabled && userId.equals(actorId);
    }
}
