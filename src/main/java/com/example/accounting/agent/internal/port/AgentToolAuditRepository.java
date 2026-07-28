package com.example.accounting.agent.internal.port;

import java.util.UUID;

public interface AgentToolAuditRepository {

    void record(String toolName, UUID ledgerId, UUID actorId, UUID traceId, String inputHash, String resultHash);
}
