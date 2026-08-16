package com.example.accounting.documents;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class JobResponses {

    private JobResponses() {
    }

    @Schema(name = "DocumentJob")
    public record Job(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ledgerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String jobType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID aggregateId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) int attempts,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) OffsetDateTime nextRunAt,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, nullable = true) String lockedBy) {
    }
}
