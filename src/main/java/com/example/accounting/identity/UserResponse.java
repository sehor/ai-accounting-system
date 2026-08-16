package com.example.accounting.identity;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(name = "CurrentUser", requiredProperties = {"id", "issuer", "subject", "displayName", "email",
        "userType", "status"})
public record UserResponse(UUID id, String issuer, String subject, String displayName,
                           @Schema(nullable = true) String email,
                           UserType userType, String status) {
}
