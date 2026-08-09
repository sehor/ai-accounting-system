package com.example.accounting.agent.internal.port;

import java.time.Instant;
import java.util.UUID;

public record AgentToolAuditEvent(
        UUID id,
        String toolName,
        UUID ledgerId,
        UUID actorId,
        String traceId,
        String inputHash,
        String resultHash,
        String outcome,
        String errorCode,
        long durationMs,
        Instant occurredAt) {
}
