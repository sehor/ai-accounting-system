package com.example.accounting.documents;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class JobResponses {

    private JobResponses() {
    }

    public record Job(UUID id, UUID ledgerId, String jobType, UUID aggregateId, String status,
                      int attempts, OffsetDateTime nextRunAt, String lockedBy) {
    }
}
