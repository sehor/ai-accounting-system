package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountManagementSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void exposesTreeControlsDimensionsVoucherControlsAndImportPreviewSchema() {
        assertThat(columnExists("ledger_account", "parent_id")).isTrue();
        assertThat(columnExists("ledger_account", "quantity_enabled")).isTrue();
        assertThat(columnExists("voucher_line", "cash_flow_item_id")).isTrue();
        assertThat(tableExists("ledger_account_dimension")).isTrue();
        assertThat(tableExists("voucher_line_dimension")).isTrue();
        assertThat(columnExists("voucher_line", "dimension_combination_id")).isTrue();
        assertThat(columnExists("opening_balance", "dimension_combination_id")).isTrue();
        assertThat(tableExists("dimension_combination")).isTrue();
        assertThat(tableExists("dimension_combination_member")).isTrue();
        assertThat(tableExists("opening_balance_dimension")).isTrue();
        assertThat(tableExists("dimension_period_balance")).isTrue();
        assertThat(foreignKeyExists("voucher_line", "fk_voucher_line_dimension_combination")).isTrue();
        assertThat(foreignKeyExists("opening_balance", "fk_opening_balance_dimension_combination")).isTrue();
        assertThat(indexExists("opening_balance", "uk_opening_balance_legacy_key")).isTrue();
        assertThat(tableExists("account_import_row")).isTrue();
    }

    private boolean columnExists(String table, String column) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.columns
                    where table_schema = current_schema() and table_name = ? and column_name = ?)
                """, Boolean.class, table, column));
    }

    private boolean tableExists(String table) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.tables
                    where table_schema = current_schema() and table_name = ?)
                """, Boolean.class, table));
    }

    private boolean foreignKeyExists(String table, String constraint) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from information_schema.table_constraints
                    where table_schema = current_schema() and table_name = ?
                      and constraint_name = ? and constraint_type = 'FOREIGN KEY')
                """, Boolean.class, table, constraint));
    }

    private boolean indexExists(String table, String index) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                select exists (
                    select 1 from pg_indexes
                    where schemaname = current_schema() and tablename = ? and indexname = ?)
                """, Boolean.class, table, index));
    }
}
