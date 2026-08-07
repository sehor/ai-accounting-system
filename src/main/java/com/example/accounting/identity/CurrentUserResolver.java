package com.example.accounting.identity;

import com.example.accounting.shared.web.ApiProblemException;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves the authenticated user, with an explicit local-only development fallback. */
@Component
public class CurrentUserResolver {

    private static final String LOCAL_USER_HEADER = "X-User-Id";
    private static final String LOCAL_USER_NAME_HEADER = "X-User-Name";
    private static final Pattern LOCAL_USER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private final boolean localHeaderEnabled;
    private final IdentityService identityService;

    @Autowired
    public CurrentUserResolver(@Value("${app.security.local-user-header-enabled:false}") boolean localHeaderEnabled,
                               IdentityService identityService) {
        this.localHeaderEnabled = localHeaderEnabled;
        this.identityService = identityService;
    }

    public CurrentUserResolver(boolean localHeaderEnabled) {
        this.localHeaderEnabled = localHeaderEnabled;
        this.identityService = null;
    }

    public UUID resolve(HttpServletRequest request) {
        return resolveUser(request).id();
    }

    public ResolvedUser resolveUser(HttpServletRequest request) {
        if (localHeaderEnabled) {
            String displayName = request.getHeader(LOCAL_USER_NAME_HEADER);
            if (displayName != null) {
                displayName = displayName.trim();
                if (!LOCAL_USER_NAME.matcher(displayName).matches()) {
                    throw new ApiProblemException(400, "INVALID_USER_NAME", "Invalid user name",
                            "X-User-Name contains unsupported characters", false);
                }
                if (identityService != null) {
                    UserResponse user = identityService.findLocalUser(displayName).orElseThrow(() ->
                            new ApiProblemException(401, "UNKNOWN_LOCAL_USER", "Unknown local user",
                                    "The local user does not exist", false));
                    return new ResolvedUser(user.id(), user.issuer(), user.subject(),
                            user.displayName(), user.email(), user.userType());
                }
            }
            String header = request.getHeader(LOCAL_USER_HEADER);
            if (header != null) {
                try {
                    UUID id = UUID.fromString(header);
                    if (identityService != null) {
                        UserResponse user = identityService.findUser(id).orElseThrow(() ->
                                new ApiProblemException(401, "UNKNOWN_LOCAL_USER", "Unknown local user",
                                        "The local user does not exist", false));
                        return new ResolvedUser(user.id(), user.issuer(), user.subject(),
                                user.displayName(), user.email(), user.userType());
                    }
                    return new ResolvedUser(id, "local", header, displayName, null);
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

    public ResolvedUser resolveAuthenticatedUserDetails() {
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
                if (identityService != null) {
                    Optional<UserResponse> existing = identityService.findUser(id);
                    if (existing.isPresent()) {
                        UserResponse user = existing.get();
                        return new ResolvedUser(user.id(), user.issuer(), user.subject(),
                                user.displayName(), user.email(), user.userType());
                    }
                }
                return new ResolvedUser(id, "local", id.toString(), null, null);
            } catch (IllegalArgumentException ignored) {
                // A non-UUID principal is not a local application user identifier.
            }
        }
        throw new ApiProblemException(401, "UNAUTHENTICATED", "Authentication required",
                "A valid user identity is required", false);
    }

    public record ResolvedUser(UUID id, String issuer, String subject, String displayName, String email,
                               UserType userType) {

        public ResolvedUser(UUID id, String issuer, String subject) {
            this(id, issuer, subject, null, null, UserType.HUMAN);
        }

        public ResolvedUser(UUID id, String issuer, String subject, String displayName, String email) {
            this(id, issuer, subject, displayName, email, UserType.HUMAN);
        }
    }
}
