package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcReportFormulaRepository implements ReportFormulaRepository {

    private static final String REVISION_SELECT = """
            select revision.id, revision.formula_id, revision.state,
                revision.definition_json::text, revision.base_published_version,
                revision.draft_version, revision.published_version, revision.source,
                revision.rollback_of_version, revision.last_previewed_draft_version,
                revision.preview_has_warnings, revision.created_by, revision.updated_by,
                revision.created_at, revision.updated_at
            from report_formula_revision revision""";

    private final JdbcTemplate jdbc;

    public JdbcReportFormulaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<Snapshot> findSnapshot(UUID ledgerId, String code) {
        return singleSnapshot(jdbc.query("""
                select id, ledger_id, code, name, formula_json::text, formula_kind, schema_version,
                    published_version, created_at, updated_at, updated_by
                from report_formula_snapshot where ledger_id = ? and code = ?
                """, JdbcReportFormulaRepository::mapSnapshot, ledgerId, code));
    }

    @Override
    public Optional<Snapshot> lockSnapshot(UUID ledgerId, String code) {
        return singleSnapshot(jdbc.query("""
                select id, ledger_id, code, name, formula_json::text, formula_kind, schema_version,
                    published_version, created_at, updated_at, updated_by
                from report_formula_snapshot where ledger_id = ? and code = ? for update
                """, JdbcReportFormulaRepository::mapSnapshot, ledgerId, code));
    }

    @Override
    public void updateSnapshotDefinition(UUID snapshotId, String formulaKind, String canonicalJson, UUID actorId) {
        jdbc.update("""
                update report_formula_snapshot
                set formula_json = ?::jsonb, formula_kind = ?, schema_version = 1,
                    updated_at = now(), updated_by = ?
                where id = ?
                """, canonicalJson, formulaKind, actorId, snapshotId);
    }

    @Override
    public void publishSnapshot(UUID snapshotId, String formulaKind, String canonicalJson,
                                int publishedVersion, UUID actorId) {
        jdbc.update("""
                update report_formula_snapshot
                set formula_json = ?::jsonb, formula_kind = ?, published_version = ?,
                    updated_at = now(), updated_by = ?
                where id = ?
                """, canonicalJson, formulaKind, publishedVersion, actorId, snapshotId);
    }

    @Override
    public void createSnapshotWithPublishedVersion(
            UUID ledgerId, String code, String name, String formulaKind, String canonicalJson, UUID actorId) {
        UUID snapshotId = UUID.randomUUID();
        jdbc.update("""
                insert into report_formula_snapshot (
                    id, ledger_id, code, name, formula_json, formula_kind, schema_version,
                    published_version, created_at, updated_at, updated_by)
                values (?, ?, ?, ?, ?::jsonb, ?, 1, 1, now(), now(), ?)
                """, snapshotId, ledgerId, code, name, canonicalJson, formulaKind, actorId);
        insertPublished(snapshotId, canonicalJson, 0, 1, "STANDARD", null, actorId);
    }

    @Override
    public boolean publishedVersionExists(UUID ledgerId, String code, int version) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from report_formula_revision revision
                    join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                    where snapshot.ledger_id = ? and snapshot.code = ?
                      and revision.state = 'PUBLISHED' and revision.published_version = ?)
                """, Boolean.class, ledgerId, code, version));
    }

    @Override
    public Optional<Revision> findDraft(UUID ledgerId, String code) {
        return singleRevision(jdbc.query("""
                %s
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ? and revision.state = 'DRAFT'
                """.formatted(REVISION_SELECT), JdbcReportFormulaRepository::mapRevision, ledgerId, code));
    }

    @Override
    public Optional<Revision> lockDraft(UUID ledgerId, String code) {
        return singleRevision(jdbc.query("""
                %s
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ? and revision.state = 'DRAFT'
                for update of revision
                """.formatted(REVISION_SELECT), JdbcReportFormulaRepository::mapRevision, ledgerId, code));
    }

    @Override
    public UUID createDraft(UUID snapshotId, String definitionJson, int basePublishedVersion, UUID actorId) {
        UUID draftId = UUID.randomUUID();
        jdbc.update("""
                insert into report_formula_revision (
                    id, formula_id, state, definition_json, base_published_version,
                    draft_version, source, created_by, updated_by)
                values (?, ?, 'DRAFT', ?::jsonb, ?, 1, 'USER', ?, ?)
                """, draftId, snapshotId, definitionJson, basePublishedVersion, actorId, actorId);
        return draftId;
    }

    @Override
    public boolean updateDraft(UUID draftId, String definitionJson, long expectedDraftVersion, UUID actorId) {
        return jdbc.update("""
                update report_formula_revision
                set definition_json = ?::jsonb, draft_version = draft_version + 1,
                    last_previewed_draft_version = null, preview_has_warnings = false,
                    updated_by = ?, updated_at = now()
                where id = ? and state = 'DRAFT' and draft_version = ?
                """, definitionJson, actorId, draftId, expectedDraftVersion) == 1;
    }

    @Override
    public void updateDraftPreviewState(UUID draftId, long previewedDraftVersion, boolean hasWarnings) {
        jdbc.update("""
                update report_formula_revision
                set last_previewed_draft_version = ?, preview_has_warnings = ?, updated_at = now()
                where id = ? and state = 'DRAFT'
                """, previewedDraftVersion, hasWarnings, draftId);
    }

    @Override
    public boolean deleteDraft(UUID draftId) {
        return jdbc.update("delete from report_formula_revision where id = ? and state = 'DRAFT'",
                draftId) == 1;
    }

    @Override
    public UUID insertPublished(UUID formulaId, String definitionJson, int basePublishedVersion,
                                int publishedVersion, String source, Integer rollbackOfVersion, UUID actorId) {
        UUID revisionId = UUID.randomUUID();
        jdbc.update("""
                insert into report_formula_revision (
                    id, formula_id, state, definition_json, base_published_version,
                    published_version, source, rollback_of_version, created_by, updated_by)
                values (?, ?, 'PUBLISHED', ?::jsonb, ?, ?, ?, ?, ?, ?)
                on conflict (formula_id, published_version) where state = 'PUBLISHED' do nothing
                """, revisionId, formulaId, definitionJson, basePublishedVersion, publishedVersion,
                source, rollbackOfVersion, actorId, actorId);
        return revisionId;
    }

    @Override
    public void recordAudit(UUID ledgerId, UUID formulaId, String action, UUID actorId,
                            String beforeJson, String afterJson) {
        jdbc.update("""
                insert into audit_revision (
                    id, ledger_id, aggregate_type, aggregate_id, revision, action,
                    actor_id, before_data, after_data)
                values (?, ?, 'REPORT_FORMULA', ?, (
                    select coalesce(max(revision), 0) + 1
                    from audit_revision
                    where ledger_id = ? and aggregate_type = 'REPORT_FORMULA' and aggregate_id = ?
                ), ?, ?, ?::jsonb, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, formulaId, ledgerId, formulaId,
                action, actorId, beforeJson, afterJson);
    }

    @Override
    public List<Revision> listPublishedVersions(UUID ledgerId, String code, int page, int pageSize) {
        return jdbc.query("""
                %s
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ? and revision.state = 'PUBLISHED'
                order by revision.published_version desc
                limit ? offset ?
                """.formatted(REVISION_SELECT), JdbcReportFormulaRepository::mapRevision,
                ledgerId, code, pageSize, (long) (page - 1) * pageSize);
    }

    @Override
    public long countPublishedVersions(UUID ledgerId, String code) {
        Long count = jdbc.queryForObject("""
                select count(*) from report_formula_revision revision
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ? and revision.state = 'PUBLISHED'
                """, Long.class, ledgerId, code);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Revision> findPublishedVersion(UUID ledgerId, String code, int version) {
        return singleRevision(jdbc.query("""
                %s
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ? and revision.state = 'PUBLISHED'
                  and revision.published_version = ?
                """.formatted(REVISION_SELECT), JdbcReportFormulaRepository::mapRevision,
                ledgerId, code, version));
    }

    @Override
    public void replaceAccountReferences(UUID revisionId, UUID ledgerId, Set<UUID> accountIds) {
        jdbc.update("delete from report_formula_account_reference where revision_id = ?", revisionId);
        for (UUID accountId : accountIds) {
            jdbc.update("""
                    insert into report_formula_account_reference (revision_id, ledger_id, account_id)
                    values (?, ?, ?)
                    """, revisionId, ledgerId, accountId);
        }
    }

    @Override
    public Set<UUID> referencedAccountIds(UUID ledgerId, String code) {
        return Set.copyOf(jdbc.queryForList("""
                select distinct reference.account_id from report_formula_account_reference reference
                join report_formula_revision revision on revision.id = reference.revision_id
                join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                where snapshot.ledger_id = ? and snapshot.code = ?
                """, UUID.class, ledgerId, code));
    }

    @Override
    public boolean accountReferenced(UUID ledgerId, String code, UUID accountId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from report_formula_account_reference reference
                    join report_formula_revision revision on revision.id = reference.revision_id
                    join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                    where snapshot.ledger_id = ? and snapshot.code = ?
                      and reference.account_id = ?)
                """, Boolean.class, ledgerId, code, accountId));
    }

    private static Optional<Snapshot> singleSnapshot(List<Snapshot> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Optional<Revision> singleRevision(List<Revision> rows) {
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static Snapshot mapSnapshot(ResultSet rs, int rowNum) throws SQLException {
        return new Snapshot(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("code"), rs.getString("name"), rs.getString("formula_json"),
                rs.getString("formula_kind"), rs.getInt("schema_version"), rs.getInt("published_version"),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class),
                rs.getObject("updated_by", UUID.class));
    }

    private static Revision mapRevision(ResultSet rs, int rowNum) throws SQLException {
        return new Revision(
                rs.getObject("id", UUID.class), rs.getObject("formula_id", UUID.class),
                rs.getString("state"), rs.getString("definition_json"), rs.getInt("base_published_version"),
                nullableLong(rs, "draft_version"), nullableInt(rs, "published_version"),
                rs.getString("source"), nullableInt(rs, "rollback_of_version"),
                nullableLong(rs, "last_previewed_draft_version"), rs.getBoolean("preview_has_warnings"),
                rs.getObject("created_by", UUID.class), rs.getObject("updated_by", UUID.class),
                rs.getObject("created_at", OffsetDateTime.class), rs.getObject("updated_at", OffsetDateTime.class));
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
