package com.example.accounting.administration.internal.persistence;

import com.example.accounting.administration.AdminResponses;
import com.example.accounting.administration.internal.port.AdministrationRepository;
import com.example.accounting.identity.UserType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAdministrationRepository implements AdministrationRepository {

    private final JdbcTemplate jdbc;

    public JdbcAdministrationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AdminResponses.User> listUsers() {
        return jdbc.query("""
                select id, issuer, subject, display_name, email, user_type, status,
                    deleted_at is not null as deleted
                from app_user
                order by deleted_at nulls first, lower(coalesce(display_name, '')), id
                """, (rs, rowNum) -> mapUser(rs));
    }

    @Override
    public Optional<AdminResponses.User> findUser(UUID userId) {
        return Optional.ofNullable(jdbc.query("""
                select id, issuer, subject, display_name, email, user_type, status,
                    deleted_at is not null as deleted
                from app_user where id = ?
                """, rs -> rs.next() ? mapUser(rs) : null, userId));
    }

    @Override
    public void deleteUser(UUID userId) {
        jdbc.update("""
                update app_user set status = 'INACTIVE', deleted_at = coalesce(deleted_at, now()),
                    updated_at = now(), version = version + 1
                where id = ?
                """, userId);
    }

    @Override
    public void restoreUser(UUID userId) {
        jdbc.update("""
                update app_user set status = 'ACTIVE', deleted_at = null,
                    updated_at = now(), version = version + 1
                where id = ?
                """, userId);
    }

    @Override
    public List<AdminResponses.Ledger> listLedgers() {
        return jdbc.query("""
                select id, name, description, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status,
                    deleted_at is not null as deleted
                from ledger
                order by deleted_at nulls first, lower(name), id
                """, (rs, rowNum) -> mapLedger(rs));
    }

    @Override
    public Optional<AdminResponses.Ledger> findLedger(UUID ledgerId) {
        return Optional.ofNullable(jdbc.query("""
                select id, name, description, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status,
                    deleted_at is not null as deleted
                from ledger where id = ?
                """, rs -> rs.next() ? mapLedger(rs) : null, ledgerId));
    }

    @Override
    public void deleteLedger(UUID ledgerId, UUID actorId) {
        jdbc.update("""
                update ledger set status = 'INACTIVE', deleted_at = coalesce(deleted_at, now()),
                    updated_at = now(), updated_by = ?, version = version + 1
                where id = ?
                """, actorId, ledgerId);
    }

    @Override
    public void restoreLedger(UUID ledgerId, UUID actorId) {
        jdbc.update("""
                update ledger set status = 'ACTIVE', deleted_at = null,
                    updated_at = now(), updated_by = ?, version = version + 1
                where id = ?
                """, actorId, ledgerId);
    }

    private AdminResponses.User mapUser(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminResponses.User(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("display_name"), rs.getString("email"),
                UserType.valueOf(rs.getString("user_type")), rs.getString("status"),
                rs.getBoolean("deleted"), false);
    }

    private AdminResponses.Ledger mapLedger(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AdminResponses.Ledger(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getString("description"), rs.getString("accounting_standard_code"),
                rs.getString("accounting_standard_version"),
                rs.getString("base_currency"), rs.getObject("start_date", LocalDate.class),
                rs.getBoolean("approval_enabled"), rs.getString("status"), rs.getBoolean("deleted"));
    }
}
