package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.AccountCodeRule;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AccountImportRepository {

    private final JdbcTemplate jdbc;

    public AccountImportRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public UUID findByHash(UUID ledgerId, String sha256) {
        return jdbc.query("""
                select id from account_import
                where ledger_id = ? and content_sha256 = ? and status = 'PREVIEW'
                order by created_at desc limit 1
                """, rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId, sha256);
    }

    public void create(UUID id, UUID ledgerId, String format, String mode, long ledgerVersion,
                       String filename, String sha256, int rowCount, int errorCount,
                       int insertedCount, int deletedCount,
                       AccountCodeRule targetRule, String codeRuleSourcesJson,
                       String blockersJson, String aiStatus, UUID actorId) {
        jdbc.update("""
                insert into account_import (
                    id, ledger_id, format, mode, ledger_version, original_filename,
                    content_sha256, row_count, error_count, inserted_count, deleted_count,
                    target_rule_level2_width, target_rule_level3_width, target_rule_level4_width,
                    code_rule_sources_json, blockers_json, ai_status, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?)
                """, id, ledgerId, format, mode, ledgerVersion, filename, sha256, rowCount, errorCount,
                insertedCount, deletedCount,
                targetRule == null ? null : targetRule.level2Width(),
                targetRule == null ? null : targetRule.level3Width(),
                targetRule == null ? null : targetRule.level4Width(),
                codeRuleSourcesJson == null ? "{}" : codeRuleSourcesJson,
                blockersJson == null ? "[]" : blockersJson, aiStatus, actorId);
    }

    public void refreshPreview(UUID importId, UUID ledgerId, String format, String mode, long ledgerVersion,
                               String filename, int rowCount, int errorCount,
                               int insertedCount, int deletedCount,
                               AccountCodeRule targetRule, String codeRuleSourcesJson,
                               String blockersJson, String aiStatus) {
        int changed = jdbc.update("""
                update account_import
                set format = ?, mode = ?, ledger_version = ?, original_filename = ?, row_count = ?,
                    error_count = ?, inserted_count = ?, deleted_count = ?,
                    target_rule_level2_width = ?, target_rule_level3_width = ?, target_rule_level4_width = ?,
                    code_rule_sources_json = ?::jsonb, blockers_json = ?::jsonb,
                    ai_status = ?, updated_at = now()
                where id = ? and ledger_id = ? and status = 'PREVIEW'
                """, format, mode, ledgerVersion, filename, rowCount, errorCount,
                insertedCount, deletedCount,
                targetRule == null ? null : targetRule.level2Width(),
                targetRule == null ? null : targetRule.level3Width(),
                targetRule == null ? null : targetRule.level4Width(),
                codeRuleSourcesJson == null ? "{}" : codeRuleSourcesJson,
                blockersJson == null ? "[]" : blockersJson, aiStatus, importId, ledgerId);
        if (changed != 1) {
            throw new IllegalStateException("Only a preview import can be refreshed");
        }
        jdbc.update("delete from account_import_row where import_id = ?", importId);
    }

    public void addRow(UUID importId, int rowNo, String rawJson, String cleanedJson, String accountCode,
                       UUID targetId, Long targetVersion, String action,
                       BigDecimal confidence, String issuesJson) {
        jdbc.update("""
                insert into account_import_row (
                    import_id, row_no, raw_data, cleaned_data, account_code,
                    target_account_id, expected_account_version, action,
                    confirmed, confidence, issues)
                values (?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, false, ?, ?::jsonb)
                """, importId, rowNo, rawJson, cleanedJson, accountCode, targetId,
                targetVersion, action, confidence, issuesJson);
    }

    public void setErrorCount(UUID importId, int errorCount) {
        jdbc.update("update account_import set error_count = ? where id = ?", errorCount, importId);
    }

    public Header findHeader(UUID ledgerId, UUID importId) {
        return jdbc.query("""
                select id, ledger_id, format, coalesce(mode, 'APPEND') as mode, status, ledger_version,
                    original_filename, content_sha256, row_count, error_count, inserted_count, deleted_count,
                    target_rule_level2_width, target_rule_level3_width, target_rule_level4_width,
                    code_rule_sources_json::text, blockers_json::text, ai_status
                from account_import where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new Header(
                rs.getObject("id", UUID.class), rs.getObject("ledger_id", UUID.class),
                rs.getString("format"), rs.getString("mode"), rs.getString("status"), rs.getLong("ledger_version"),
                rs.getString("original_filename"), rs.getString("content_sha256"), rs.getInt("row_count"),
                rs.getInt("error_count"), rs.getInt("inserted_count"), rs.getInt("deleted_count"),
                rs.getObject("target_rule_level2_width", Integer.class),
                rs.getObject("target_rule_level3_width", Integer.class),
                rs.getObject("target_rule_level4_width", Integer.class),
                rs.getString("code_rule_sources_json"), rs.getString("blockers_json"),
                rs.getString("ai_status")) : null, ledgerId, importId);
    }

    public List<PreviewRow> previewRows(UUID importId) {
        return jdbc.query("""
                select row_no, raw_data::text, cleaned_data::text, account_code,
                    target_account_id, expected_account_version, action, confirmed,
                    confidence, issues::text
                from account_import_row where import_id = ? order by row_no
                """, (rs, row) -> new PreviewRow(
                rs.getInt("row_no"), rs.getString("raw_data"), rs.getString("cleaned_data"),
                rs.getString("account_code"), rs.getObject("target_account_id", UUID.class),
                rs.getObject("expected_account_version", Long.class), rs.getString("action"),
                rs.getBoolean("confirmed"), rs.getBigDecimal("confidence"),
                rs.getString("issues")), importId);
    }

    public int decide(UUID importId, int rowNo, String action, UUID targetId,
                      Long targetVersion) {
        return jdbc.update("""
                update account_import_row
                set action = ?, target_account_id = ?, expected_account_version = ?,
                    confirmed = true
                where import_id = ? and row_no = ?
                """, action, targetId, targetVersion, importId, rowNo);
    }

    public List<CommitRow> commitRows(UUID importId) {
        return jdbc.query("""
                select row_no, cleaned_data::text, target_account_id,
                    expected_account_version, action, confirmed, issues::text
                from account_import_row where import_id = ? order by row_no
                """, (rs, row) -> new CommitRow(
                rs.getInt("row_no"), rs.getString("cleaned_data"),
                rs.getObject("target_account_id", UUID.class),
                rs.getObject("expected_account_version", Long.class), rs.getString("action"),
                rs.getBoolean("confirmed"), rs.getString("issues")), importId);
    }

    public void markCommitted(UUID importId, UUID ledgerId, int insertedCount, int deletedCount) {
        jdbc.update("""
                update account_import
                set status = 'COMMITTED', inserted_count = ?, deleted_count = ?, updated_at = now()
                where id = ? and ledger_id = ? and status = 'PREVIEW'
                """, insertedCount, deletedCount, importId, ledgerId);
    }

    public record Header(
            UUID id, UUID ledgerId, String format, String mode, String status, long ledgerVersion,
            String filename, String contentSha256, int rowCount, int errorCount, int insertedCount, int deletedCount,
            Integer targetRuleLevel2Width, Integer targetRuleLevel3Width, Integer targetRuleLevel4Width,
            String codeRuleSourcesJson, String blockersJson, String aiStatus) {
    }

    public record PreviewRow(
            int rowNo, String rawJson, String cleanedJson, String accountCode,
            UUID targetId, Long targetVersion, String action, boolean confirmed,
            BigDecimal confidence, String issuesJson) {
    }

    public record CommitRow(
            int rowNo, String cleanedJson, UUID targetId, Long targetVersion,
            String action, boolean confirmed, String issuesJson) {
    }
}
