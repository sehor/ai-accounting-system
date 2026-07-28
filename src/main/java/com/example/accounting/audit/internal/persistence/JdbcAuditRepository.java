package com.example.accounting.audit.internal.persistence;

import com.example.accounting.audit.AuditResponses;
import com.example.accounting.audit.internal.port.AuditRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAuditRepository implements AuditRepository {

    private final JdbcTemplate jdbc;

    public JdbcAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AuditResponses.Entry> list(UUID ledgerId) {
        return jdbc.query("""
                select id, aggregate_type, aggregate_id, revision, action, actor_id, reason, created_at
                from audit_revision where ledger_id = ? order by created_at, revision
                """, (rs, rowNum) -> new AuditResponses.Entry(rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getInt("revision"),
                rs.getString("action"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                rs.getObject("created_at", OffsetDateTime.class)), ledgerId);
    }
}
