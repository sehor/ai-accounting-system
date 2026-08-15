package com.example.accounting.audit;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AuditResponses {

    private AuditResponses() {
    }

    @Schema(requiredProperties = {"id", "aggregateType", "aggregateId", "revision", "action", "actorId",
            "reason", "createdAt"})
    public record Entry(UUID id, String aggregateType, UUID aggregateId, int revision, String action,
                        UUID actorId, @Schema(nullable = true) String reason, OffsetDateTime createdAt) {
    }

    @Schema(name = "AuditPage", requiredProperties = {"items", "nextCursor", "hasMore"})
    public record Page(List<Entry> items, @Schema(nullable = true) String nextCursor, boolean hasMore) {
    }
}
