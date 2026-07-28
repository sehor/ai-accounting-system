package com.example.accounting.identity;

import com.example.accounting.shared.web.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user, with an explicit local-only development fallback. */
@Component
public class CurrentUserResolver {

    private static final String LOCAL_USER_HEADER = "X-User-Id";
    private final boolean localHeaderEnabled;

    public CurrentUserResolver(@Value("${app.security.local-user-header-enabled:false}") boolean localHeaderEnabled) {
        this.localHeaderEnabled = localHeaderEnabled;
    }

    public UUID resolve(HttpServletRequest request) {
        return resolveUser(request).id();
    }

    public ResolvedUser resolveUser(HttpServletRequest request) {
        if (localHeaderEnabled) {
            String header = request.getHeader(LOCAL_USER_HEADER);
            if (header != null) {
                try {
                    UUID id = UUID.fromString(header);
                    return new ResolvedUser(id, "local", header, null, null);
                } catch (IllegalArgumentException exception) {
                    throw new ApiProblemException(400, "INVALID_USER_ID", "Invalid user ID",
                            "X-User-Id must be a UUID", false);
                }
            }
        }

        return resolveAuthenticatedUserDetails();
    }

    public UUID resolveAuthenticatedUser() {
        return resolveAuthenticatedUserDetails().id();
    }

    private ResolvedUser resolveAuthenticatedUserDetails() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            String issuer = token.getToken().getIssuer() == null ? "" : token.getToken().getIssuer().toString();
            String subject = token.getToken().getSubject();
            String displayName = token.getToken().getClaimAsString("name");
            String email = token.getToken().getClaimAsString("email");
            return new ResolvedUser(UUID.nameUUIDFromBytes((issuer + "\u0000" + subject)
                    .getBytes(StandardCharsets.UTF_8)), issuer, subject, displayName, email);
        }
        if (authentication != null && authentication.isAuthenticated()) {
            try {
                UUID id = UUID.fromString(authentication.getName());
                return new ResolvedUser(id, "local", id.toString(), null, null);
            } catch (IllegalArgumentException ignored) {
                // A non-UUID principal is not a local application user identifier.
            }
        }
        throw new ApiProblemException(401, "UNAUTHENTICATED", "Authentication required",
                "A valid user identity is required", false);
    }

    public record ResolvedUser(UUID id, String issuer, String subject, String displayName, String email) {

        public ResolvedUser(UUID id, String issuer, String subject) {
            this(id, issuer, subject, null, null);
        }
    }
}
