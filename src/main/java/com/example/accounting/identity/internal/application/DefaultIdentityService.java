package com.example.accounting.identity.internal.application;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.internal.port.IdentityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.Optional;
import java.util.UUID;

@Service
public class DefaultIdentityService implements IdentityService {

    private final IdentityRepository users;

    public DefaultIdentityService(IdentityRepository users) {
        this.users = users;
    }

    @Override
    @Transactional
    public UserResponse ensureUser(CurrentUserResolver.ResolvedUser actor) {
        String subject = actor.subject() == null ? actor.id().toString() : actor.subject();
        String fallback = "User " + subject.substring(0, Math.min(8, subject.length()));
        String displayName = actor.displayName() == null || actor.displayName().isBlank()
                ? fallback : actor.displayName().trim();
        if ("local".equals(actor.issuer())) {
            Optional<UserResponse> existing = users.findByLocalUsername(displayName);
            if (existing.isPresent()) {
                return existing.get();
            }
        }

        return users.upsert(actor.id(), actor.issuer(), subject, displayName, actor.email(), actor.userType());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponse> findLocalUser(String username) {
        return users.findByLocalUsername(username.trim());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponse> findUser(UUID id) {
        return users.findById(id);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UserResponse> findByEmail(String email) {
        return users.findByEmail(email.trim().toLowerCase(java.util.Locale.ROOT));
    }
}
