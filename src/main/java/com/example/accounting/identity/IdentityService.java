package com.example.accounting.identity;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {

    private final JdbcTemplate jdbcTemplate;

    public IdentityService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public UserResponse ensureUser(CurrentUserResolver.ResolvedUser actor) {
        jdbcTemplate.update("""
                insert into app_user (id, issuer, subject, display_name)
                values (?, ?, ?, ?)
                on conflict (id) do update set issuer = excluded.issuer, subject = excluded.subject,
                    updated_at = now(), deleted_at = null, status = 'ACTIVE'
                """, actor.id(), actor.issuer(), actor.subject(), "User " + actor.subject().substring(0,
                Math.min(8, actor.subject().length())));
        return jdbcTemplate.queryForObject("""
                select id, issuer, subject, display_name, email, status
                from app_user where id = ? and deleted_at is null
                """, (rs, rowNum) -> new UserResponse(rs.getObject("id", UUID.class), rs.getString("issuer"),
                rs.getString("subject"), rs.getString("display_name"), rs.getString("email"), rs.getString("status")),
                actor.id());
    }
}
