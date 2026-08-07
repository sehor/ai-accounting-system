package com.example.accounting.administration;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@Profile("local")
public class LocalPlatformAdminBootstrap {

    private final IdentityService identities;
    private final boolean enabled;
    private final UUID userId;
    private final String username;

    public LocalPlatformAdminBootstrap(
            IdentityService identities,
            @Value("${app.security.platform-admin-enabled:false}") boolean enabled,
            @Value("${app.security.platform-admin-user-id:a2757c7a-fb97-4979-8f4f-abe3e401dacc}") UUID userId,
            @Value("${app.security.platform-admin-username:admin}") String username) {
        this.identities = identities;
        this.enabled = enabled;
        this.userId = userId;
        this.username = username;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void ensureAdminExists() {
        if (!enabled) {
            return;
        }
        UserResponse admin = identities.ensureUser(new CurrentUserResolver.ResolvedUser(
                userId, "local", userId.toString(), username, null, UserType.HUMAN));
        if (!admin.id().equals(userId)) {
            throw new IllegalStateException("The configured platform administrator name belongs to another user");
        }
    }
}
