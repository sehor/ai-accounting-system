package com.example.accounting.agent.internal.port;

import java.util.UUID;

public interface AgentToolAuditRepository {

    void recordSuccess(
            String toolName, UUID ledgerId, UUID actorId, String traceId, String inputHash, String resultHash);

    void recordFailure(String toolName, UUID ledgerId, UUID actorId, String traceId,
                       String inputHash, String errorCode, String errorHash);
}
