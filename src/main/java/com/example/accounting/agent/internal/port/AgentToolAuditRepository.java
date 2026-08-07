package com.example.accounting.agent.internal.port;

import java.util.List;

public interface AgentToolAuditRepository {

    void recordBatch(List<AgentToolAuditEvent> events);
}
