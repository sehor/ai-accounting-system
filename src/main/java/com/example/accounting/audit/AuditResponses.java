package com.example.accounting.audit;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class AuditResponses {

    private AuditResponses() {
    }

    public record Entry(UUID id, String aggregateType, UUID aggregateId, int revision, String action,
                        UUID actorId, String reason, OffsetDateTime createdAt) {
    }

    public record Page(List<Entry> items, String nextCursor, boolean hasMore) {
    }
}
