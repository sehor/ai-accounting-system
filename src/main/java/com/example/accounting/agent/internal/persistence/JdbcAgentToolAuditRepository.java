package com.example.accounting.agent.internal.persistence;

import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentToolAuditRepository implements AgentToolAuditRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentToolAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void recordSuccess(String toolName, UUID ledgerId, UUID actorId, String traceId,
                              String inputHash, String resultHash) {
        jdbc.update("""
                insert into agent_tool_audit
                    (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash, outcome)
                values (?, ?, ?, ?, ?, ?, ?, 'SUCCESS')
                """, UUID.randomUUID(), toolName, ledgerId, actorId, traceId, inputHash, resultHash);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordFailure(String toolName, UUID ledgerId, UUID actorId, String traceId,
                              String inputHash, String errorCode, String errorHash) {
        jdbc.update("""
                insert into agent_tool_audit
                    (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash, outcome, error_code)
                values (?, ?, ?, ?, ?, ?, ?, 'FAILURE', ?)
                """, UUID.randomUUID(), toolName, ledgerId, actorId, traceId, inputHash, errorHash, errorCode);
    }
}
