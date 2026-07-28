package com.example.accounting.agent.internal.persistence;

import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAgentToolAuditRepository implements AgentToolAuditRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentToolAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void record(String toolName, UUID ledgerId, UUID actorId, UUID traceId,
                       String inputHash, String resultHash) {
        jdbc.update("""
                insert into agent_tool_audit (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash)
                values (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), toolName, ledgerId, actorId, traceId, inputHash, resultHash);
    }
}
