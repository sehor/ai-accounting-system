package com.example.accounting.identity.internal.port;

import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
import java.util.UUID;
import java.util.Optional;

public interface IdentityRepository {

    UserResponse upsert(UUID id, String issuer, String subject, String displayName, String email, UserType userType);

    Optional<UserResponse> findByLocalUsername(String username);

    Optional<UserResponse> findById(UUID id);

    Optional<UserResponse> findByIdIncludingDeleted(UUID id);

    Optional<UserResponse> findByEmail(String email);
}
