package com.example.accounting.identity;

import java.util.Optional;

public interface IdentityService {

    UserResponse ensureUser(CurrentUserResolver.ResolvedUser actor);

    Optional<UserResponse> findByEmail(String email);
}
