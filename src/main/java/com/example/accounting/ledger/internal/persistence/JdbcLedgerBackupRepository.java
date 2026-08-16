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
            Map.entry("dimension_value", "id"), Map.entry("dimension_combination", "id"),
            Map.entry("dimension_combination_member", "combination_id, dimension_type_id"),
            Map.entry("ledger_account", "level, id"),
            Map.entry("ledger_account_dimension", "account_id, dimension_type_id"),
            Map.entry("accounting_period", "start_date, id"), Map.entry("opening_balance", "id"),
            Map.entry("opening_balance_dimension", "opening_balance_id, dimension_type_id"),
            Map.entry("voucher", "voucher_date, id"), Map.entry("voucher_line", "voucher_id, line_no"),
            Map.entry("voucher_line_dimension", "voucher_line_id, dimension_type_id"),
            Map.entry("voucher_approval", "created_at, id"),
            Map.entry("period_action_audit", "created_at, id"),
            Map.entry("report_formula_snapshot", "id"), Map.entry("audit_revision", "created_at, id"),
            Map.entry("document", "created_at, id"), Map.entry("document_extraction", "created_at, id"),
            Map.entry("agent_tool_audit", "created_at, id"),
            Map.entry("accounting_experience", "updated_at, id"),
            Map.entry("fixed_asset_category", "code, id"),
            Map.entry("fixed_asset", "code, id"),
            Map.entry("fixed_asset_change", "created_at, id"),
            Map.entry("fixed_asset_depreciation_run", "created_at, id"),
            Map.entry("fixed_asset_depreciation_line", "period_id, asset_id, id"),
            Map.entry("fixed_asset_disposal", "disposal_date, id"),
            Map.entry("fixed_asset_import_batch", "created_at, id"),
            Map.entry("fixed_asset_import_row", "row_no, id"));

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
    public void createLedger(UUID ledgerId, UUID actorId, String name, String description, String standardCode,
                             String standardVersion, String baseCurrency, LocalDate startDate,
                             boolean approvalEnabled, String separator, int level2Width,
                             int level3Width, int level4Width) {
        jdbc.update("""
                insert into ledger (id, name, description, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, approval_enabled, status, created_by, updated_by,
                    account_code_separator, account_level2_width, account_level3_width, account_level4_width)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?)
                """, ledgerId, name, description, standardCode, standardVersion, baseCurrency, startDate,
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
                where table_schema = current_schema() and table_name = ? order by ordinal_position
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

    @Override
    public void backfillLegacyDimensionCombinations(UUID ledgerId) {
        jdbc.update("""
                with voucher_keys as (
                    select line.ledger_id,
                        'v1;' || coalesce((select string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';'
                            from voucher_line_dimension member
                            where member.ledger_id = line.ledger_id
                              and member.voucher_line_id = line.id), '') canonical_key
                    from voucher_line line where line.ledger_id = ?
                ), opening_keys as (
                    select balance.ledger_id,
                        case when balance.dimension_key = '' then 'v1;'
                             else 'legacy-v1;' || balance.dimension_key end canonical_key,
                        case when balance.dimension_key = '' then 'STRUCTURED'
                             else 'LEGACY_UNMAPPED' end kind
                    from opening_balance balance
                    where balance.ledger_id = ? and balance.dimension_combination_id is null
                ), all_keys as (
                    select ledger_id, canonical_key, 'STRUCTURED'::varchar kind from voucher_keys
                    union select ledger_id, canonical_key, kind from opening_keys
                )
                insert into dimension_combination (id, ledger_id, kind, canonical_key, dimension_key)
                select (md5('dimension-combination:' || ledger_id::text || ':' || canonical_key))::uuid,
                    ledger_id, kind, canonical_key, md5(canonical_key)
                from all_keys on conflict (ledger_id, canonical_key) do nothing
                """, ledgerId, ledgerId);
        jdbc.update("""
                with voucher_keys as (
                    select line.id line_id, line.ledger_id,
                        'v1;' || coalesce((select string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';'
                            from voucher_line_dimension member
                            where member.ledger_id = line.ledger_id
                              and member.voucher_line_id = line.id), '') canonical_key
                    from voucher_line line where line.ledger_id = ?
                )
                insert into dimension_combination_member (
                    ledger_id, combination_id, dimension_type_id, dimension_value_id,
                    dimension_type_code, dimension_type_name, dimension_value_code, dimension_value_name)
                select member.ledger_id, combination.id, member.dimension_type_id, member.dimension_value_id,
                    type.code, type.name, value.code, value.name
                from voucher_line_dimension member
                join voucher_keys key
                  on key.ledger_id = member.ledger_id and key.line_id = member.voucher_line_id
                join dimension_combination combination
                  on combination.ledger_id = key.ledger_id and combination.canonical_key = key.canonical_key
                join dimension_type type
                  on type.ledger_id = member.ledger_id and type.id = member.dimension_type_id
                join dimension_value value
                  on value.ledger_id = member.ledger_id
                 and value.dimension_type_id = member.dimension_type_id
                 and value.id = member.dimension_value_id
                on conflict (ledger_id, combination_id, dimension_type_id) do nothing
                """, ledgerId);
        jdbc.update("""
                with voucher_keys as (
                    select line.id line_id, line.ledger_id,
                        'v1;' || coalesce((select string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';'
                            from voucher_line_dimension member
                            where member.ledger_id = line.ledger_id
                              and member.voucher_line_id = line.id), '') canonical_key
                    from voucher_line line where line.ledger_id = ?
                )
                update voucher_line line set dimension_combination_id = combination.id
                from voucher_keys key join dimension_combination combination
                  on combination.ledger_id = key.ledger_id and combination.canonical_key = key.canonical_key
                where line.ledger_id = key.ledger_id and line.id = key.line_id
                """, ledgerId);
        jdbc.update("""
                update opening_balance balance set dimension_combination_id = combination.id,
                    dimension_key = combination.dimension_key
                from dimension_combination combination
                where balance.ledger_id = ? and balance.dimension_combination_id is null
                  and combination.ledger_id = balance.ledger_id
                  and combination.canonical_key = case when balance.dimension_key = '' then 'v1;'
                      else 'legacy-v1;' || balance.dimension_key end
                """, ledgerId);
    }

    @Override
    public void normalizeRestoredDimensionCombinations(UUID ledgerId) {
        Integer conflicts = jdbc.queryForObject("""
                with expected as (
                    select combination.id,
                        'v1;' || coalesce(string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';', '') canonical_key
                    from dimension_combination combination
                    left join dimension_combination_member member
                      on member.ledger_id = combination.ledger_id
                     and member.combination_id = combination.id
                    where combination.ledger_id = ? and combination.kind = 'STRUCTURED'
                    group by combination.id
                ), duplicate_expected as (
                    select canonical_key from expected group by canonical_key having count(*) > 1
                ), occupied_expected as (
                    select expected.canonical_key
                    from expected
                    join dimension_combination occupied
                      on occupied.ledger_id = ?
                     and occupied.canonical_key = expected.canonical_key
                     and occupied.id <> expected.id
                )
                select (select count(*) from duplicate_expected)
                     + (select count(*) from occupied_expected)
                """, Integer.class, ledgerId, ledgerId);
        if (conflicts == null || conflicts != 0) {
            throw new IllegalArgumentException(
                    "Restored structured dimension combinations do not have unique member sets");
        }

        jdbc.update("""
                with expected as (
                    select combination.id,
                        'v1;' || coalesce(string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';', '') canonical_key
                    from dimension_combination combination
                    left join dimension_combination_member member
                      on member.ledger_id = combination.ledger_id
                     and member.combination_id = combination.id
                    where combination.ledger_id = ? and combination.kind = 'STRUCTURED'
                    group by combination.id
                )
                update dimension_combination combination
                set canonical_key = expected.canonical_key,
                    dimension_key = md5(expected.canonical_key)
                from expected
                where combination.ledger_id = ? and combination.id = expected.id
                """, ledgerId, ledgerId);
        jdbc.update("""
                update opening_balance balance
                set dimension_key = combination.dimension_key
                from dimension_combination combination
                where balance.ledger_id = ?
                  and combination.ledger_id = balance.ledger_id
                  and combination.id = balance.dimension_combination_id
                  and combination.kind = 'STRUCTURED'
                """, ledgerId);

        Integer inconsistent = jdbc.queryForObject("""
                with expected as (
                    select combination.id, combination.canonical_key, combination.dimension_key,
                        'v1;' || coalesce(string_agg(
                            member.dimension_type_id::text || '=' || member.dimension_value_id::text,
                            ';' order by member.dimension_type_id::text) || ';', '') expected_key
                    from dimension_combination combination
                    left join dimension_combination_member member
                      on member.ledger_id = combination.ledger_id
                     and member.combination_id = combination.id
                    where combination.ledger_id = ? and combination.kind = 'STRUCTURED'
                    group by combination.id
                )
                select count(*) from expected
                where canonical_key <> expected_key or dimension_key <> md5(expected_key)
                """, Integer.class, ledgerId);
        if (inconsistent == null || inconsistent != 0) {
            throw new IllegalArgumentException(
                    "Restored structured dimension combinations are inconsistent with their members");
        }
    }

    private String requireTable(String table) {
        String orderBy = TABLES.get(table);
        if (orderBy == null) throw new IllegalArgumentException("Unsupported backup table");
        return orderBy;
    }
}
