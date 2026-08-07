package com.example.accounting.ledger.internal.application;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalSuperAgentBootstrap {

    private final IdentityService identities;
    private final LedgerRepository ledgers;
    private final boolean enabled;
    private final UUID userId;
    private final String username;

    public LocalSuperAgentBootstrap(
            IdentityService identities,
            LedgerRepository ledgers,
            @Value("${app.security.local-super-agent-enabled:false}") boolean enabled,
            @Value("${app.security.local-super-agent-user-id:00000000-0000-4000-8000-000000000099}") UUID userId,
            @Value("${app.security.local-super-agent-username:super-agent}") String username) {
        this.identities = identities;
        this.ledgers = ledgers;
        this.enabled = enabled;
        this.userId = userId;
        this.username = username;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void synchronize() {
        if (!enabled) {
            return;
        }
        UserResponse agent = identities.ensureUser(new CurrentUserResolver.ResolvedUser(
                userId, "local", username, username, null, UserType.AGENT));
        if (!agent.id().equals(userId)) {
            throw new IllegalStateException("The configured local super-agent name belongs to another user");
        }
        for (UUID ledgerId : ledgers.listAllLedgerIds()) {
            ledgers.upsertMember(ledgerId, userId, LedgerRole.EDITOR, userId);
        }
    }
}
