package com.example.accounting.identity.internal.persistence;

import com.example.accounting.identity.UserResponse;
import com.example.accounting.identity.UserType;
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
    public UserResponse upsert(UUID id, String issuer, String subject, String displayName, String email,
                               UserType userType) {
        jdbc.update("""
                insert into app_user (id, issuer, subject, display_name, email, user_type)
                values (?, ?, ?, ?, ?, ?)
                on conflict (id) do update set issuer = excluded.issuer, subject = excluded.subject,
                    display_name = excluded.display_name, email = excluded.email, user_type = excluded.user_type,
                    updated_at = now(), deleted_at = null, status = 'ACTIVE'
                """, id, issuer, subject, displayName,
                email == null ? null : email.toLowerCase(java.util.Locale.ROOT), userType.name());
        return jdbc.queryForObject("""
                select id, issuer, subject, display_name, email, user_type, status
                from app_user where id = ? and deleted_at is null
                """, this::mapUser, id);
    }

    @Override
    public Optional<UserResponse> findByLocalUsername(String username) {
        return Optional.ofNullable(jdbc.query("""
                select id, issuer, subject, display_name, email, user_type, status
                from app_user
                where issuer = 'local' and lower(display_name) = lower(?) and deleted_at is null
                """, rs -> rs.next() ? mapUser(rs, 0) : null, username));
    }

    @Override
    public Optional<UserResponse> findById(UUID id) {
        return Optional.ofNullable(jdbc.query("""
                select id, issuer, subject, display_name, email, user_type, status
                from app_user where id = ? and deleted_at is null
                """, rs -> rs.next() ? mapUser(rs, 0) : null, id));
    }

    @Override
    public Optional<UserResponse> findByEmail(String email) {
        return Optional.ofNullable(jdbc.query("""
                select id, issuer, subject, display_name, email, user_type, status
                from app_user where lower(email) = ? and status = 'ACTIVE' and deleted_at is null
                """, rs -> rs.next() ? mapUser(rs, 0) : null, email));
    }

    private UserResponse mapUser(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new UserResponse(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("display_name"), rs.getString("email"),
                UserType.valueOf(rs.getString("user_type")), rs.getString("status"));
    }
}
