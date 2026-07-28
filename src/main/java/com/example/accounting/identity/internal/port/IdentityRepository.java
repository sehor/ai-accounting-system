package com.example.accounting.identity.internal.port;

import com.example.accounting.identity.UserResponse;
import java.util.UUID;
import java.util.Optional;

public interface IdentityRepository {

    UserResponse upsert(UUID id, String issuer, String subject, String displayName, String email);

    Optional<UserResponse> findByEmail(String email);
}
