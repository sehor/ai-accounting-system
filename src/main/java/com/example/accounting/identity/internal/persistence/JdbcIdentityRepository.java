package com.example.accounting.identity.internal.persistence;

import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.internal.port.IdentityRepository;
import java.util.UUID;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcIdentityRepository implements IdentityRepository {

    private final JdbcTemplate jdbc;

    public JdbcIdentityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public UserResponse upsert(UUID id, String issuer, String subject, String displayName, String email) {
        jdbc.update("""
                insert into app_user (id, issuer, subject, display_name, email)
                values (?, ?, ?, ?, ?)
                on conflict (id) do update set issuer = excluded.issuer, subject = excluded.subject,
                    display_name = excluded.display_name, email = excluded.email,
                    updated_at = now(), deleted_at = null, status = 'ACTIVE'
                """, id, issuer, subject, displayName, email == null ? null : email.toLowerCase(java.util.Locale.ROOT));
        return jdbc.queryForObject("""
                select id, issuer, subject, display_name, email, status
                from app_user where id = ? and deleted_at is null
                """, (rs, rowNum) -> new UserResponse(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("display_name"), rs.getString("email"), rs.getString("status")),
                id);
    }

    @Override
    public Optional<UserResponse> findByEmail(String email) {
        return Optional.ofNullable(jdbc.query("""
                select id, issuer, subject, display_name, email, status
                from app_user where lower(email) = ? and status = 'ACTIVE' and deleted_at is null
                """, rs -> rs.next() ? new UserResponse(rs.getObject("id", UUID.class),
                rs.getString("issuer"), rs.getString("subject"), rs.getString("display_name"),
                rs.getString("email"), rs.getString("status")) : null, email));
    }
}
