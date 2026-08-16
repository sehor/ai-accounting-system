package com.example.accounting.administration;

import io.swagger.v3.oas.annotations.media.Schema;
import com.example.accounting.identity.UserType;
import java.time.LocalDate;
import java.util.UUID;

public final class AdminResponses {

    private AdminResponses() {
    }

    @Schema(name = "AdminUser", requiredProperties = {"id", "issuer", "subject", "displayName", "email",
            "userType", "status", "deleted", "protectedUser"})
    public record User(UUID id, String issuer, String subject, String displayName,
                       @Schema(nullable = true) String email,
                       UserType userType, String status, boolean deleted, boolean protectedUser) {
        User withProtectedUser(boolean value) {
            return new User(id, issuer, subject, displayName, email, userType, status, deleted, value);
        }
    }

    @Schema(name = "AdminLedger", requiredProperties = {"id", "name", "description",
            "accountingStandardCode", "accountingStandardVersion", "baseCurrency", "startDate",
            "approvalEnabled", "status", "deleted"})
    public record Ledger(UUID id, String name, String description,
                         String accountingStandardCode, String accountingStandardVersion,
                         String baseCurrency, LocalDate startDate, boolean approvalEnabled,
                         String status, boolean deleted) {

        public Ledger(UUID id, String name, String accountingStandardCode, String accountingStandardVersion,
                      String baseCurrency, LocalDate startDate, boolean approvalEnabled,
                      String status, boolean deleted) {
            this(id, name, "", accountingStandardCode, accountingStandardVersion, baseCurrency, startDate,
                    approvalEnabled, status, deleted);
        }
    }
}
