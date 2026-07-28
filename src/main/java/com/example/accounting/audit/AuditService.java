package com.example.accounting.audit;

import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditService {

    private final JdbcTemplate jdbcTemplate;

    public AuditService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(readOnly = true)
    public List<AuditResponses.Entry> list(UUID actorId, UUID ledgerId) {
        requireRole(actorId, ledgerId);
        return jdbcTemplate.query("""
                select id, aggregate_type, aggregate_id, revision, action, actor_id, reason, created_at
                from audit_revision where ledger_id = ? order by created_at, revision
                """, (rs, rowNum) -> new AuditResponses.Entry(rs.getObject("id", UUID.class),
                rs.getString("aggregate_type"), rs.getObject("aggregate_id", UUID.class), rs.getInt("revision"),
                rs.getString("action"), rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)), ledgerId);
    }

    private void requireRole(UUID actorId, UUID ledgerId) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw new ApiProblemException(404, "LEDGER_NOT_FOUND", "Ledger not found",
                    "The ledger is not available to this user", false);
        }
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER,
                LedgerRole.VIEWER, LedgerRole.AGENT).contains(LedgerRole.valueOf(role))) {
            throw new ApiProblemException(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view audit records", false);
        }
    }
}
