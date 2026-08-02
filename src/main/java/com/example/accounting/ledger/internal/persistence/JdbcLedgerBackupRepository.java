package com.example.accounting.ledger.internal.persistence;

import com.example.accounting.ledger.internal.port.LedgerBackupRepository;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcLedgerBackupRepository implements LedgerBackupRepository {

    private static final Map<String, String> TABLES = Map.ofEntries(
            Map.entry("cash_flow_item", "id"), Map.entry("dimension_type", "id"),
            Map.entry("dimension_value", "id"), Map.entry("ledger_account", "level, id"),
            Map.entry("ledger_account_dimension", "account_id, dimension_type_id"),
            Map.entry("accounting_period", "start_date, id"), Map.entry("opening_balance", "id"),
            Map.entry("voucher", "voucher_date, id"), Map.entry("voucher_line", "voucher_id, line_no"),
            Map.entry("voucher_line_dimension", "voucher_line_id, dimension_type_id"),
            Map.entry("voucher_approval", "created_at, id"),
            Map.entry("period_action_audit", "created_at, id"),
            Map.entry("report_formula_snapshot", "id"), Map.entry("audit_revision", "created_at, id"),
            Map.entry("document", "created_at, id"), Map.entry("document_extraction", "created_at, id"),
            Map.entry("agent_tool_audit", "created_at, id"));

    private final JdbcTemplate jdbc;

    public JdbcLedgerBackupRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public String ledgerJson(UUID ledgerId) {
        return jdbc.query("select to_jsonb(l)::text from ledger l where id = ? and deleted_at is null",
                result -> result.next() ? result.getString(1) : null, ledgerId);
    }

    @Override
    public String rowsJson(String table, UUID ledgerId) {
        String orderBy = requireTable(table);
        return jdbc.queryForObject("select coalesce(jsonb_agg(to_jsonb(t) order by " + orderBy
                + "), '[]'::jsonb)::text from " + table + " t where ledger_id = ?",
                String.class, ledgerId);
    }

    @Override
    public void createLedger(UUID ledgerId, UUID actorId, String name, String standardCode,
                             String standardVersion, String baseCurrency, LocalDate startDate,
                             boolean approvalEnabled, String separator, int level2Width,
                             int level3Width, int level4Width) {
        jdbc.update("""
                insert into ledger (id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status, created_by, updated_by,
                    account_code_separator, account_level2_width, account_level3_width, account_level4_width)
                values (?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, ledgerId, name, standardCode, standardVersion, baseCurrency, startDate,
                approvalEnabled, actorId, actorId, separator, level2Width, level3Width, level4Width);
    }

    @Override
    public void createOwner(UUID ledgerId, UUID actorId) {
        jdbc.update("""
                insert into ledger_membership (id, ledger_id, user_id, role, created_by, updated_by)
                values (?, ?, ?, 'OWNER', ?, ?)
                """, UUID.randomUUID(), ledgerId, actorId, actorId, actorId);
    }

    @Override
    public Map<String, String> columns(String table) {
        requireTable(table);
        Map<String, String> result = new LinkedHashMap<>();
        jdbc.query("""
                select column_name, udt_name from information_schema.columns
                where table_schema = 'public' and table_name = ? order by ordinal_position
                """, resultSet -> {
            while (resultSet.next()) result.put(resultSet.getString(1), resultSet.getString(2));
            return null;
        }, table);
        return result;
    }

    @Override
    public void insertRow(String table, LinkedHashMap<String, Object> values, Set<String> jsonColumns) {
        requireTable(table);
        if (values.keySet().stream().anyMatch(name -> !name.matches("[a-z][a-z0-9_]*"))) {
            throw new IllegalArgumentException("Invalid backup column");
        }
        String columns = values.keySet().stream().map(name -> '"' + name + '"')
                .collect(Collectors.joining(", "));
        String placeholders = values.keySet().stream().map(name -> jsonColumns.contains(name)
                        ? "cast(? as jsonb)" : "?")
                .collect(Collectors.joining(", "));
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                    "insert into " + table + " (" + columns + ") values (" + placeholders + ")");
            int index = 1;
            for (Object value : values.values()) statement.setObject(index++, value);
            return statement;
        });
    }

    private String requireTable(String table) {
        String orderBy = TABLES.get(table);
        if (orderBy == null) throw new IllegalArgumentException("Unsupported backup table");
        return orderBy;
    }
}
