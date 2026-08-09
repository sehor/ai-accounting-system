package com.example.accounting.agent.internal.persistence;

import com.example.accounting.agent.ExperienceScope;
import com.example.accounting.agent.internal.port.AccountingExperienceRepository;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcAccountingExperienceRepository implements AccountingExperienceRepository {

    private final JdbcTemplate jdbc;

    public JdbcAccountingExperienceRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Record create(ExperienceScope scope, UUID ledgerId, String title, String content, List<String> tags,
                         UUID actorId) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                insert into accounting_experience
                    (id, scope, ledger_id, title, content, tags, created_by, updated_by)
                values (?, ?, ?, ?, ?, ?, ?, ?)
                """, id, scope.name(), ledgerId, title, content, tags.toArray(String[]::new), actorId, actorId);
        return find(id).orElseThrow();
    }

    @Override
    public Page search(UUID ledgerId, String query, List<String> tags, int limit, int offset) {
        StringBuilder where = new StringBuilder("status = 'ACTIVE'");
        List<Object> args = new ArrayList<>();
        if (ledgerId == null) {
            where.append(" and ledger_id is null");
        } else {
            where.append(" and (ledger_id is null or ledger_id = ?)");
            args.add(ledgerId);
        }
        if (query != null && !query.isBlank()) {
            where.append(" and (title ilike ? escape '\\' or content ilike ? escape '\\')");
            String pattern = "%" + escapeLike(query) + "%";
            args.add(pattern);
            args.add(pattern);
        }
        for (String tag : tags) {
            where.append(" and ? = any(tags)");
            args.add(tag);
        }

        String base = " from accounting_experience where " + where;
        Long total = jdbc.queryForObject("select count(*)" + base, Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        List<Record> items = jdbc.query("select id, scope, ledger_id, title, content, tags, status, version, "
                        + "created_by, updated_by, created_at, updated_at" + base
                        + " order by updated_at desc, id limit ? offset ?", (result, rowNum) -> map(result),
                pageArgs.toArray());
        return new Page(items, total == null ? 0 : total);
    }

    @Override
    public Optional<Record> find(UUID experienceId) {
        return jdbc.query("""
                select id, scope, ledger_id, title, content, tags, status, version,
                       created_by, updated_by, created_at, updated_at
                from accounting_experience where id = ?
                """, result -> result.next() ? Optional.of(map(result)) : Optional.empty(), experienceId);
    }

    @Override
    public boolean update(UUID experienceId, long expectedVersion, String title, String content, List<String> tags,
                          UUID actorId) {
        return jdbc.update("""
                update accounting_experience
                   set title = ?, content = ?, tags = ?, updated_by = ?, updated_at = now(), version = version + 1
                 where id = ? and status = 'ACTIVE' and version = ?
                """, title, content, tags.toArray(String[]::new), actorId, experienceId, expectedVersion) == 1;
    }

    @Override
    public boolean archive(UUID experienceId, long expectedVersion, UUID actorId) {
        return jdbc.update("""
                update accounting_experience
                   set status = 'ARCHIVED', updated_by = ?, updated_at = now(), version = version + 1
                 where id = ? and status = 'ACTIVE' and version = ?
                """, actorId, experienceId, expectedVersion) == 1;
    }

    private Record map(ResultSet result) throws SQLException {
        return new Record(result.getObject("id", UUID.class), ExperienceScope.valueOf(result.getString("scope")),
                result.getObject("ledger_id", UUID.class), result.getString("title"), result.getString("content"),
                readTags(result.getArray("tags")), result.getString("status"), result.getLong("version"),
                result.getObject("created_by", UUID.class), result.getObject("updated_by", UUID.class),
                result.getObject("created_at", java.time.OffsetDateTime.class),
                result.getObject("updated_at", java.time.OffsetDateTime.class));
    }

    private List<String> readTags(Array array) throws SQLException {
        if (array == null) return List.of();
        Object value = array.getArray();
        if (!(value instanceof String[] values)) return List.of();
        return List.of(values);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
