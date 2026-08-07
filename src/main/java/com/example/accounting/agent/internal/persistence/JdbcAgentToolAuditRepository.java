package com.example.accounting.agent.internal.persistence;

import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import com.example.accounting.agent.internal.port.AgentToolAuditEvent;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcAgentToolAuditRepository implements AgentToolAuditRepository {

    private final JdbcTemplate jdbc;

    public JdbcAgentToolAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public void recordBatch(List<AgentToolAuditEvent> events) {
        jdbc.batchUpdate("""
                insert into agent_tool_audit
                    (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash,
                     outcome, error_code, duration_ms, created_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement statement, int index) throws SQLException {
                        AgentToolAuditEvent event = events.get(index);
                        statement.setObject(1, event.id());
                        statement.setString(2, event.toolName());
                        statement.setObject(3, event.ledgerId());
                        statement.setObject(4, event.actorId());
                        statement.setString(5, event.traceId());
                        statement.setString(6, event.inputHash());
                        statement.setString(7, event.resultHash());
                        statement.setString(8, event.outcome());
                        statement.setString(9, event.errorCode());
                        statement.setLong(10, event.durationMs());
                        statement.setTimestamp(11, Timestamp.from(event.occurredAt()));
                    }

                    @Override
                    public int getBatchSize() {
                        return events.size();
                    }
                });
    }
}
