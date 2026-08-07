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
                select m.role from ledger_membership m
                join ledger l on l.id = m.ledger_id
                join app_user u on u.id = m.user_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.status = 'ACTIVE' and l.deleted_at is null
                    and u.status = 'ACTIVE' and u.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        return Optional.ofNullable(role).map(LedgerRole::valueOf);
    }

    @Override
    public boolean activeLedgerExists(UUID ledgerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (select 1 from ledger
                    where id = ? and status = 'ACTIVE' and deleted_at is null)
                """, Boolean.class, ledgerId));
    }
}
