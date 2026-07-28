package com.example.accounting.identity;

import java.util.UUID;

public record UserResponse(UUID id, String issuer, String subject, String displayName, String email, String status) {
}
