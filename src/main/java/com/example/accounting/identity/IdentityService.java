package com.example.accounting.identity;

import java.util.Optional;
import java.util.UUID;

public interface IdentityService {

    UserResponse ensureUser(CurrentUserResolver.ResolvedUser actor);

    Optional<UserResponse> findLocalUser(String username);

    Optional<UserResponse> findUser(UUID id);

    Optional<UserResponse> findByEmail(String email);
}
