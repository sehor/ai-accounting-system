package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.internal.port.LedgerAccessRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLedgerAccessRepository implements LedgerAccessRepository {

    private final JdbcTemplate jdbc;

    public JdbcLedgerAccessRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<LedgerRole> findRole(UUID actorId, UUID ledgerId) {
        String role = jdbc.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        return Optional.ofNullable(role).map(LedgerRole::valueOf);
    }
}
