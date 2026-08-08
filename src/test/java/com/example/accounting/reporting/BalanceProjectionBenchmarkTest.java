package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Repeatable database benchmark for the balance projection acceptance target.
 *
 * Run explicitly with {@code -Dtest=BalanceProjectionBenchmarkTest}; it is not
 * part of the normal test suite because the default dataset contains one million
 * voucher lines. Each run owns and drops a random PostgreSQL schema.
 */
@EnabledIfEnvironmentVariable(named = "RUN_BALANCE_PROJECTION_BENCHMARK", matches = "true")
class BalanceProjectionBenchmarkTest {

    private static final String LEGACY_SQL = """
            select a.id, a.code, a.name, a.category,
                coalesce(sum(x.debit), 0) debit, coalesce(sum(x.credit), 0) credit
            from ledger_account a
            left join (
                select vl.account_id,
                    case when vl.side = 'DEBIT' then vl.base_amount else 0 end debit,
                    case when vl.side = 'CREDIT' then vl.base_amount else 0 end credit
                from voucher_line vl
                join voucher v on v.ledger_id = vl.ledger_id and v.id = vl.voucher_id
                join accounting_period p on p.ledger_id = v.ledger_id and p.id = v.period_id
                where v.ledger_id = ? and v.status = 'POSTED'
                  and (?::varchar is null or p.period_code = ?)
                union all
                select ob.account_id, ob.debit_base, ob.credit_base
                from opening_balance ob
                join accounting_period p on p.ledger_id = ob.ledger_id and p.id = ob.period_id
                where ob.ledger_id = ? and ob.confirmed
                  and (?::varchar is null or p.period_code = ?)
            ) x on x.account_id = a.id
            where a.ledger_id = ?
            group by a.id, a.code, a.name, a.category
            having coalesce(sum(x.debit), 0) <> 0 or coalesce(sum(x.credit), 0) <> 0
            order by a.code
            """;

    private static final String PROJECTION_SQL = """
            select a.id, a.code, a.name, a.category,
                coalesce(sum(b.opening_debit_base + b.period_debit_base), 0) debit,
                coalesce(sum(b.opening_credit_base + b.period_credit_base), 0) credit
            from ledger_account a
            left join account_period_balance b on b.ledger_id = a.ledger_id and b.account_id = a.id
            left join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                and (?::varchar is null or p.period_code = ?)
            where a.ledger_id = ? and (b.account_id is null or p.id is not null)
            group by a.id, a.code, a.name, a.category
            having coalesce(sum(b.opening_debit_base + b.period_debit_base), 0) <> 0
                or coalesce(sum(b.opening_credit_base + b.period_credit_base), 0) <> 0
            order by a.code
            """;

    private static final String STATUS_SQL = """
            select s.status, coalesce(s.last_enqueued_event_id, 0) enqueued,
                coalesce(s.last_applied_event_id, 0) applied
            from balance_projection_state s
            join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
            where s.ledger_id = ? and (?::varchar is null or p.period_code = ?)
            """;

    @Test
    @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    void benchmarksOneMillionVoucherLinesInAnIsolatedSchema() throws Exception {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();
        String schema = "bench_balance_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = open(config)) {
            try {
                if (Boolean.parseBoolean(System.getenv().getOrDefault("BENCHMARK_CLEANUP_STALE", "false"))) {
                    cleanupStaleSchemas(connection);
                }
                System.out.println("balance-benchmark creating schema=" + schema);
                createSchemaAndMigrate(connection, config, schema);
                System.out.println("balance-benchmark migrations-complete schema=" + schema);
                connection.createStatement().execute("set search_path to " + quoteIdentifier(schema));
                Fixture fixture = seed(connection, config.voucherLines());
                connection.commit();
                analyze(connection);
                System.out.println("balance-benchmark fixture=" + fixture.voucherLines()
                        + " lines accounts=" + fixture.accountCount() + " schema=" + schema);

                BenchmarkResult legacy = benchmark(connection, config, fixture, Mode.LEGACY);
                BenchmarkResult projection = benchmark(connection, config, fixture, Mode.PROJECTION);
                BenchmarkResult fallback = benchmark(connection, config, fixture, Mode.FALLBACK);
                System.out.println(toJson(config, fixture, legacy, projection, fallback));
                assertThat(legacy.rows()).isEqualTo(projection.rows());
                assertThat(legacy.checksum()).isEqualByComparingTo(projection.checksum());
                assertThat(fallback.rows()).isEqualTo(legacy.rows());
                assertThat(fallback.checksum()).isEqualByComparingTo(legacy.checksum());
            } finally {
                dropSchema(connection, schema);
            }
        }
    }

    private Connection open(BenchmarkConfig config) throws SQLException {
        Connection connection = DriverManager.getConnection(config.url(), config.username(), config.password());
        connection.setAutoCommit(false);
        return connection;
    }

    private void createSchemaAndMigrate(Connection connection, BenchmarkConfig config, String schema) {
        try (Statement statement = connection.createStatement()) {
            statement.execute("create schema " + quoteIdentifier(schema));
            connection.commit();
        } catch (SQLException exception) {
            throw new IllegalStateException("Could not create benchmark schema " + schema, exception);
        }
        Flyway.configure()
                .dataSource(config.url(), config.username(), config.password())
                .schemas(schema)
                .defaultSchema(schema)
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    private Fixture seed(Connection connection, int voucherLines) throws SQLException {
        int accounts = Math.max(20, Integer.parseInt(System.getenv().getOrDefault("BENCHMARK_ACCOUNTS", "100")));
        int linesPerVoucher = 4;
        int vouchers = Math.max(1, (voucherLines + linesPerVoucher - 1) / linesPerVoucher);
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        System.out.println("balance-benchmark seeding user/ledger/accounts");
        insertUser(connection, userId);
        insertLedger(connection, userId, ledgerId);
        insertPeriod(connection, ledgerId, periodId);
        insertAccounts(connection, ledgerId, accounts);
        System.out.println("balance-benchmark seeding " + vouchers + " vouchers and "
                + vouchers * linesPerVoucher + " voucher lines");
        insertVouchers(connection, userId, ledgerId, periodId, vouchers);
        insertVoucherLines(connection, ledgerId, periodId, vouchers, accounts, linesPerVoucher);
        System.out.println("balance-benchmark building account_period_balance");
        insertProjection(connection, ledgerId, periodId, vouchers, accounts, linesPerVoucher);
        return new Fixture(ledgerId, periodId, vouchers * linesPerVoucher, accounts);
    }

    private void insertUser(Connection connection, UUID userId) throws SQLException {
        execute(connection, """
                insert into app_user (id, issuer, subject, display_name, email)
                values (?, 'benchmark', ?, 'benchmark', ?)
                """, userId, userId.toString(), userId + "@benchmark.invalid");
    }

    private void insertLedger(Connection connection, UUID userId, UUID ledgerId) throws SQLException {
        execute(connection, """
                insert into ledger (id, name, accounting_standard_code, accounting_standard_version,
                    base_currency, start_date, created_by, updated_by)
                values (?, 'balance benchmark', 'SME', 'v1', 'CNY', date '2026-01-01', ?, ?)
                """, ledgerId, userId, userId);
    }

    private void insertPeriod(Connection connection, UUID ledgerId, UUID periodId) throws SQLException {
        execute(connection, """
                insert into accounting_period (id, ledger_id, period_code, start_date, end_date)
                values (?, ?, '2026-01', date '2026-01-01', date '2026-01-31')
                """, periodId, ledgerId);
    }

    private void insertAccounts(Connection connection, UUID ledgerId, int accounts) throws SQLException {
        execute(connection, """
                insert into ledger_account (id, ledger_id, code, name, category, normal_balance, level)
                select md5('benchmark-account-' || n)::uuid, ?, lpad(n::text, 4, '0'),
                    'Benchmark account ' || n,
                    case when n % 3 = 0 then 'REVENUE' when n % 3 = 1 then 'ASSET' else 'EXPENSE' end,
                    case when n % 3 = 0 then 'CREDIT' else 'DEBIT' end, 1
                from generate_series(1, ?) n
                """, ledgerId, accounts);
    }

    private void insertVouchers(Connection connection, UUID userId, UUID ledgerId,
                                 UUID periodId, int vouchers) throws SQLException {
        execute(connection, """
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, status, posted_at, posted_by, created_by, updated_by, version)
                select md5('benchmark-voucher-' || n)::uuid, ?, ?, date '2026-01-15', 'BENCH',
                    lpad(n::text, 32, '0'), 'benchmark', 'POSTED', now(), ?, ?, ?, 1
                from generate_series(1, ?) n
                """, ledgerId, periodId, userId, userId, userId, vouchers);
    }

    private void insertVoucherLines(Connection connection, UUID ledgerId, UUID periodId,
                                     int vouchers, int accounts, int linesPerVoucher) throws SQLException {
        execute(connection, """
                insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side,
                    currency, original_amount, exchange_rate, base_amount, summary)
                select md5('benchmark-line-' || ((v.n - 1) * ? + l.n))::uuid, ?,
                    md5('benchmark-voucher-' || v.n)::uuid, l.n,
                    md5('benchmark-account-' || (((v.n - 1) * ? + l.n - 1) % ? + 1))::uuid,
                    case when l.n % 2 = 0 then 'CREDIT' else 'DEBIT' end,
                    'CNY', 10 + ((v.n + l.n) % 100), 1, 10 + ((v.n + l.n) % 100), 'benchmark'
                from generate_series(1, ?) v(n)
                cross join generate_series(1, ?) l(n)
                """, linesPerVoucher, ledgerId, linesPerVoucher, accounts, vouchers, linesPerVoucher);
    }

    private void insertProjection(Connection connection, UUID ledgerId, UUID periodId,
                                   int vouchers, int accounts, int linesPerVoucher) throws SQLException {
        List<UUID> accountIds = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select id from ledger_account where ledger_id = ? order by code")) {
            statement.setObject(1, ledgerId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    accountIds.add(resultSet.getObject(1, UUID.class));
                }
            }
        }
        BigDecimal[] debits = new BigDecimal[accounts];
        BigDecimal[] credits = new BigDecimal[accounts];
        java.util.Arrays.fill(debits, BigDecimal.ZERO);
        java.util.Arrays.fill(credits, BigDecimal.ZERO);
        for (int voucher = 1; voucher <= vouchers; voucher++) {
            for (int line = 1; line <= linesPerVoucher; line++) {
                int account = ((voucher - 1) * linesPerVoucher + line - 1) % accounts;
                BigDecimal amount = BigDecimal.valueOf(10 + ((voucher + line) % 100));
                if (line % 2 == 0) {
                    credits[account] = credits[account].add(amount);
                } else {
                    debits[account] = debits[account].add(amount);
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement("""
                insert into account_period_balance (ledger_id, period_id, account_id,
                    period_debit_base, period_credit_base, version)
                values (?, ?, ?, ?, ?, 1)
                """)) {
            for (int account = 0; account < accounts; account++) {
                statement.setObject(1, ledgerId);
                statement.setObject(2, periodId);
                statement.setObject(3, accountIds.get(account));
                statement.setBigDecimal(4, debits[account]);
                statement.setBigDecimal(5, credits[account]);
                statement.addBatch();
            }
            statement.executeBatch();
        }
        execute(connection, """
                insert into balance_projection_state (ledger_id, period_id, last_enqueued_event_id,
                    last_applied_event_id, last_enqueued_at, projected_at, status)
                values (?, ?, 0, 0, now(), now(), 'READY')
                """, ledgerId, periodId);
    }

    private BenchmarkResult benchmark(Connection connection, BenchmarkConfig config,
                                      Fixture fixture, Mode mode) throws SQLException {
        List<Long> samples = new ArrayList<>();
        for (int i = 0; i < config.warmups() + config.iterations(); i++) {
            long started = System.nanoTime();
            QueryResult result = executeBenchmarkQuery(connection, fixture, mode);
            long elapsed = Duration.ofNanos(System.nanoTime() - started).toNanos();
            if (i >= config.warmups()) {
                samples.add(elapsed / 1_000_000);
            }
            if (result.rows() != fixture.accountCount()) {
                throw new AssertionError("Unexpected result rows for " + mode + ": " + result.rows());
            }
        }
        Collections.sort(samples);
        long sum = samples.stream().mapToLong(Long::longValue).sum();
        QueryResult result = executeBenchmarkQuery(connection, fixture, mode);
        return new BenchmarkResult(mode.name().toLowerCase(Locale.ROOT), samples.size(),
                percentile(samples, 0.50), percentile(samples, 0.95), percentile(samples, 0.99),
                samples.get(0), samples.get(samples.size() - 1), sum / (double) samples.size(),
                result.rows(), result.checksum());
    }

    private QueryResult executeBenchmarkQuery(Connection connection, Fixture fixture, Mode mode) throws SQLException {
        if (mode == Mode.FALLBACK) {
            try (PreparedStatement status = connection.prepareStatement(STATUS_SQL)) {
                status.setObject(1, fixture.ledgerId());
                status.setString(2, "2026-01");
                status.setString(3, "2026-01");
                try (ResultSet ignored = status.executeQuery()) {
                    while (ignored.next()) {
                        // Simulate the projection freshness decision before falling back.
                    }
                }
            }
            return runQuery(connection, LEGACY_SQL, fixture, true);
        }
        return runQuery(connection, mode == Mode.LEGACY ? LEGACY_SQL : PROJECTION_SQL, fixture,
                mode == Mode.LEGACY);
    }

    private QueryResult runQuery(Connection connection, String sql, Fixture fixture,
                                 boolean legacy) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            if (legacy) {
                statement.setObject(1, fixture.ledgerId());
                statement.setString(2, "2026-01");
                statement.setString(3, "2026-01");
                statement.setObject(4, fixture.ledgerId());
                statement.setString(5, "2026-01");
                statement.setString(6, "2026-01");
                statement.setObject(7, fixture.ledgerId());
            } else {
                statement.setString(1, "2026-01");
                statement.setString(2, "2026-01");
                statement.setObject(3, fixture.ledgerId());
            }
            try (ResultSet resultSet = statement.executeQuery()) {
                int rows = 0;
                BigDecimal checksum = BigDecimal.ZERO;
                while (resultSet.next()) {
                    rows++;
                    checksum = checksum.add(resultSet.getBigDecimal("debit"))
                            .subtract(resultSet.getBigDecimal("credit"));
                }
                return new QueryResult(rows, checksum);
            }
        }
    }

    private void analyze(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("analyze voucher");
            statement.execute("analyze voucher_line");
            statement.execute("analyze account_period_balance");
        }
    }

    private void dropSchema(Connection connection, String schema) throws SQLException {
        connection.rollback();
        try (Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists " + quoteIdentifier(schema) + " cascade");
        }
        connection.commit();
    }

    private void cleanupStaleSchemas(Connection connection) throws SQLException {
        List<String> stale = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "select nspname from pg_namespace where nspname like 'bench_balance_%'")) {
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    stale.add(resultSet.getString(1));
                }
            }
        }
        for (String schema : stale) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists " + quoteIdentifier(schema) + " cascade");
            }
        }
        if (!stale.isEmpty()) {
            connection.commit();
            System.out.println("balance-benchmark removed stale schemas=" + stale.size());
        }
    }

    private void execute(Connection connection, String sql, Object... parameters) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                statement.setObject(i + 1, parameters[i]);
            }
            statement.executeUpdate();
        }
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private String toJson(BenchmarkConfig config, Fixture fixture, BenchmarkResult... results) {
        String resultJson = java.util.Arrays.stream(results).map(result -> "\"" + result.mode() + "\":" + result.toJson())
                .collect(Collectors.joining(","));
        return "{\"database\":\"" + config.url() + "\",\"voucherLines\":" + fixture.voucherLines()
                + ",\"warmups\":" + config.warmups() + ",\"iterations\":" + config.iterations()
                + ",\"results\":{" + resultJson + "}}";
    }

    private enum Mode {
        LEGACY, PROJECTION, FALLBACK
    }

    private record Fixture(UUID ledgerId, UUID periodId, int voucherLines, int accountCount) {
    }

    private record QueryResult(int rows, BigDecimal checksum) {
    }

    private record BenchmarkResult(String mode, int samples, long p50Ms, long p95Ms, long p99Ms,
                                   long minMs, long maxMs, double meanMs, int rows, BigDecimal checksum) {
        String toJson() {
            return "{\"samples\":" + samples + ",\"p50Ms\":" + p50Ms + ",\"p95Ms\":" + p95Ms
                    + ",\"p99Ms\":" + p99Ms + ",\"minMs\":" + minMs + ",\"maxMs\":" + maxMs
                    + ",\"meanMs\":" + String.format(Locale.ROOT, "%.2f", meanMs)
                    + ",\"rows\":" + rows + ",\"checksum\":\"" + checksum + "\"}";
        }
    }

    private record BenchmarkConfig(String url, String username, String password,
                                   int voucherLines, int warmups, int iterations) {
        static BenchmarkConfig fromEnvironment() {
            return new BenchmarkConfig(
                    env("DB_URL", "jdbc:postgresql://localhost:5432/ai-accounting"),
                    env("DB_USERNAME", "postgres"), env("DB_PASSWORD", "pzr123"),
                    positiveInt("BENCHMARK_VOUCHER_LINES", 1_000_000),
                    positiveInt("BENCHMARK_WARMUPS", 5), positiveInt("BENCHMARK_ITERATIONS", 30));
        }

        private static String env(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value;
        }

        private static int positiveInt(String name, int fallback) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                return fallback;
            }
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) {
                throw new IllegalArgumentException(name + " must be positive");
            }
            return parsed;
        }
    }
}
