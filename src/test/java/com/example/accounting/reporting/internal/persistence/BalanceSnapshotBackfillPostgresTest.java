package com.example.accounting.reporting.internal.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** Opt-in real PostgreSQL SQL regression tests; all writes are to transaction-local temporary tables. */
@EnabledIfEnvironmentVariable(named = "ACCOUNTING_JDBC_TEST_URL", matches = ".+")
class BalanceSnapshotBackfillPostgresTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private BalanceSnapshotRebuilder snapshots;
    private final UUID ledger = UUID.randomUUID();
    private final UUID june = UUID.randomUUID();
    private final UUID july = UUID.randomUUID();
    private final UUID august = UUID.randomUUID();
    private final UUID cash = UUID.randomUUID();
    private final UUID capital = UUID.randomUUID();
    private final UUID combination = UUID.randomUUID();

    @BeforeEach
    void setUp() throws Exception {
        connection = DriverManager.getConnection(System.getenv("ACCOUNTING_JDBC_TEST_URL"),
                System.getenv("ACCOUNTING_JDBC_TEST_USER"), System.getenv("ACCOUNTING_JDBC_TEST_PASSWORD"));
        connection.setAutoCommit(false);
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("create temp table ledger (id uuid primary key, created_by uuid, deleted_at timestamptz) on commit drop");
        jdbc.execute("create temp table accounting_period (id uuid, ledger_id uuid, period_code text, status text) on commit drop");
        jdbc.execute("create temp table ledger_account (id uuid, ledger_id uuid, parent_id uuid) on commit drop");
        for (String table : new String[] {"account_period_balance", "dimension_period_balance", "opening_balance",
                "voucher", "voucher_line", "voucher_line_dimension", "dimension_combination",
                "balance_rebuild_job", "balance_rebuild_audit"}) {
            jdbc.execute("create temp table " + table + " (like public." + table + " including defaults including identity) on commit drop");
        }
        // An empty dimension combination is ordinary accounting, not an enabled auxiliary type.
        jdbc.update("insert into ledger values (?, ?, null)", ledger, UUID.randomUUID());
        jdbc.update("insert into accounting_period values (?, ?, '2026-06', 'CLOSED'), (?, ?, '2026-07', 'OPEN'), (?, ?, '2026-08', 'OPEN')",
                june, ledger, july, ledger, august, ledger);
        jdbc.update("insert into ledger_account values (?, ?, null), (?, ?, null)", cash, ledger, capital, ledger);
        jdbc.update("insert into dimension_combination (id, ledger_id, kind, canonical_key, dimension_key) values (?, ?, 'STRUCTURED', 'v1;', md5('v1;'))",
                combination, ledger);
        opening(cash, 100, 0);
        opening(capital, 0, 100);
        snapshots = new BalanceSnapshotRebuilder(jdbc, new DimensionBalanceSnapshotRebuilder(jdbc));
        snapshots.rebuildAll(ledger);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (connection != null) {
            connection.rollback();
            connection.close();
        }
    }

    @Test
    void repairsMissingClosedPeriodDetailsBeforeRollingAnOrdinaryAccountForward() {
        jdbc.update("delete from dimension_period_balance");
        snapshots.rebuildFromWithStats(ledger, july, july);
        assertThat(balance(june, cash, true)).isEqualByComparingTo("100");
        assertThat(balance(july, cash, true)).isEqualByComparingTo("100");
        assertThat(balance(july, cash, false)).isEqualByComparingTo("100");
        assertThat(jdbc.queryForObject("select status from accounting_period where id = ?", String.class, june)).isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("select count(*) from dimension_period_balance where period_id = ?", Integer.class, august)).isZero();
    }

    @Test
    void repairsPartialHistoryAndPreservesHealthyHistoryOnTheNextIncrementalRun() {
        jdbc.update("delete from dimension_period_balance where period_id = ? and account_id = ?", june, cash);
        snapshots.rebuildFromWithStats(ledger, july, august);
        assertThat(balance(june, cash, true)).isEqualByComparingTo("100");
        assertThat(balance(july, capital, true)).isEqualByComparingTo("-100");
        jdbc.update("update account_period_balance set version = 17 where period_id = ?", june);
        snapshots.rebuildFromWithStats(ledger, july, august);
        assertThat(jdbc.queryForObject("select min(version) from account_period_balance where period_id = ?", Integer.class, june)).isEqualTo(17);
    }

    @Test
    void queuesOneAuditedFullRangeBackfillOnlyForMissingHistory() throws Exception {
        String migration = Files.readString(Path.of("src/main/resources/db/migration/V64__backfill_missing_dimension_balances.sql"));
        jdbc.execute(migration);
        assertThat(jdbc.queryForObject("select count(*) from balance_rebuild_job", Integer.class)).isZero();
        jdbc.update("delete from dimension_period_balance where period_id = ?", june);
        jdbc.execute(migration);
        jdbc.execute(migration);
        assertThat(jdbc.queryForObject("select count(*) from balance_rebuild_job", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select period_from || '/' || period_to from balance_rebuild_job", String.class)).isEqualTo("2026-06/2026-08");
        assertThat(jdbc.queryForObject("select count(*) from balance_rebuild_audit", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from opening_balance", Integer.class)).isEqualTo(2);
    }

    private void opening(UUID account, int debit, int credit) {
        jdbc.update("""
                insert into opening_balance (id, ledger_id, period_id, account_id, currency, dimension_combination_id,
                    debit_original, credit_original, debit_base, credit_base, confirmed)
                values (?, ?, ?, ?, 'CNY', ?, ?, ?, ?, ?, true)
                """, UUID.randomUUID(), ledger, june, account, combination, debit, credit, debit, credit);
    }

    private java.math.BigDecimal balance(UUID period, UUID account, boolean details) {
        return jdbc.queryForObject("select closing_debit_base - closing_credit_base from "
                + (details ? "dimension_period_balance" : "account_period_balance")
                + " where ledger_id = ? and period_id = ? and account_id = ?",
                java.math.BigDecimal.class, ledger, period, account);
    }
}
