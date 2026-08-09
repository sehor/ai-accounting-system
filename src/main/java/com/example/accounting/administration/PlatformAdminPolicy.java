package com.example.accounting.administration;

import com.example.accounting.shared.web.ApiProblemException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class PlatformAdminPolicy {

    private final boolean enabled;
    private final UUID userId;
    private final UUID protectedAgentId;

    public PlatformAdminPolicy(
            @Value("${app.security.platform-admin-enabled:false}") boolean enabled,
            @Value("${app.security.platform-admin-user-id:a2757c7a-fb97-4979-8f4f-abe3e401dacc}") UUID userId,
            @Value("${app.security.local-super-agent-user-id:00000000-0000-4000-8000-000000000099}")
            UUID protectedAgentId) {
        this.enabled = enabled;
        this.userId = userId;
        this.protectedAgentId = protectedAgentId;
    }

    public boolean isPlatformAdmin(UUID actorId) {
        return enabled && userId.equals(actorId);
    }

    public void requirePlatformAdmin(UUID actorId) {
        if (!isPlatformAdmin(actorId)) {
            throw new ApiProblemException(403, "PLATFORM_ADMIN_REQUIRED", "Platform administrator required",
                    "Only the platform administrator can perform this operation", false);
        }
    }

    public boolean isProtectedUser(UUID userId) {
        return this.userId.equals(userId) || protectedAgentId.equals(userId);
    }
}
