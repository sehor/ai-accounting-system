package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;

@SpringBootTest
class AccountManagementSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LedgerService ledgers;

    @Test
    void exposesTreeControlsDimensionsVoucherControlsAndImportPreviewSchema() {
        assertThat(columnExists("ledger_account", "parent_id")).isTrue();
        assertThat(columnExists("ledger_account", "quantity_enabled")).isTrue();
        assertThat(columnExists("ledger_account", "standard_account_key")).isTrue();
        assertThat(indexExists("ledger_account", "ix_ledger_account_ledger_standard_key")).isTrue();
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
        assertThat(triggerDefinition("tr_ledger_account_hierarchy_and_lock"))
                .contains("standard_account_key");
    }

    @Test
    void rejectsADirectStandardAccountKeyOnlyUpdate() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(
                new CurrentUserResolver.ResolvedUser(owner, "schema-key", owner.toString()),
                new LedgerRequests.Create("schema-key", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();

        assertThatThrownBy(() -> jdbc.update("""
                update ledger_account set standard_account_key = 'ASSET.CASH'
                where ledger_id = ? and code = '1002'
                """, ledgerId)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("""
                select standard_account_key from ledger_account where ledger_id = ? and code = '1002'
                """, String.class, ledgerId)).isEqualTo("ASSET.BANK_DEPOSIT");
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

    private String triggerDefinition(String trigger) {
        return jdbc.queryForObject("""
                select pg_get_triggerdef(oid) from pg_trigger
                where tgname = ? and not tgisinternal
                """, String.class, trigger);
    }
}
