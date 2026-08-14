package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Benchmarks the application service path for posted voucher writes and the reporting read paths.
 *
 * <p>The benchmark is opt-in because it seeds a configurable multi-period workload. The test context
 * uses the isolated-schema customizer, and also refuses to run unless connected to ai-accounting-test.</p>
 */
@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
@EnabledIfEnvironmentVariable(named = "RUN_ACCOUNTING_PROJECTION_WORKLOAD_BENCHMARK", matches = "true")
class AccountingProjectionWorkloadBenchmarkTest {

    private static final int TARGET_PERIOD_INDEX = 9;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private ReportingService reports;

    @Autowired
    private BalanceProjectionRepository projection;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Timeout(value = 20, unit = java.util.concurrent.TimeUnit.MINUTES)
    void benchmarksPeriodTenVoucherChangesAndReportingReads() {
        BenchmarkConfig config = BenchmarkConfig.fromEnvironment();
        assertThat(jdbc.queryForObject("select current_database()", String.class))
                .isEqualTo("ai-accounting-test");

        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(actorId, "benchmark", actorId.toString()),
                new LedgerRequests.Create("projection workload", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        List<Period> periods = createPeriods(ledgerId, config.periods());
        UUID cashAccountId = accountId(ledgerId, "1001");
        UUID capitalAccountId = accountId(ledgerId, "3001");
        seedPostedVouchers(actorId, ledgerId, periods, cashAccountId, capitalAccountId, config.vouchersPerPeriod());
        drainProjection();

        Period targetPeriod = periods.get(TARGET_PERIOD_INDEX);
        FutureProjection futureBefore = futureProjection(ledgerId, targetPeriod.code());
        List<MeasuredVoucher> created = measureCreatedVouchers(
                actorId, ledgerId, targetPeriod, cashAccountId, capitalAccountId, config);
        Metric createTransaction = metric("posted-create-transaction", samples(created,
                MeasuredVoucher::transactionMilliseconds));
        Metric createWorkerCatchUp = metric("posted-create-worker-catch-up", samples(created,
                MeasuredVoucher::workerCatchUpMilliseconds));
        WriteMetrics update = measureUpdates(actorId, ledgerId, targetPeriod, cashAccountId, capitalAccountId, created);
        assertProjectionReady(ledgerId, targetPeriod.id());

        FutureProjection futureAfter = futureProjection(ledgerId, targetPeriod.code());
        assertThat(futureAfter).isNotEqualTo(futureBefore);

        Period finalPeriod = periods.getLast();
        assertThat(reports.trialBalance(actorId, ledgerId, targetPeriod.code())).isNotEmpty();
        assertThat(reports.generalLedgerBook(actorId, ledgerId, finalPeriod.code(), 1, 500).data()).isNotEmpty();
        assertThat(reports.subLedgerBook(actorId, ledgerId, finalPeriod.code(), cashAccountId, 1, 500).data())
                .isNotEmpty();

        Metric trialBalance = measure("trial-balance-projection", config,
                () -> reports.trialBalance(actorId, ledgerId, targetPeriod.code()));
        Metric trialBalanceWithParents = measure("trial-balance-with-parents-projection", config,
                () -> reports.trialBalance(actorId, ledgerId, targetPeriod.code(), true));
        Metric generalLedger = measure("general-ledger-book-projection", config,
                () -> reports.generalLedgerBook(actorId, ledgerId, finalPeriod.code(), 1, 500));
        Metric subLedger = measure("sub-ledger-book-projection", config,
                () -> reports.subLedgerBook(actorId, ledgerId, finalPeriod.code(), cashAccountId, 1, 500));

        System.out.println("{" +
                "\"database\":\"ai-accounting-test\"," +
                "\"periods\":" + config.periods() + "," +
                "\"vouchersPerPeriod\":" + config.vouchersPerPeriod() + "," +
                "\"targetPeriod\":\"" + targetPeriod.code() + "\"," +
                "\"futurePeriodProjectionChanged\":true," +
                "\"metrics\":[" + String.join(",", createTransaction.toJson(), createWorkerCatchUp.toJson(),
                update.transaction().toJson(), update.workerCatchUp().toJson(),
                trialBalance.toJson(), trialBalanceWithParents.toJson(), generalLedger.toJson(), subLedger.toJson()) +
                "]}");
    }

    private List<Period> createPeriods(UUID ledgerId, int count) {
        YearMonth firstMonth = YearMonth.of(2026, 1);
        List<Period> periods = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            YearMonth month = firstMonth.plusMonths(index);
            UUID periodId = existingPeriodId(ledgerId, month.toString());
            if (periodId == null) {
                periodId = UUID.randomUUID();
                jdbc.update("""
                        insert into accounting_period (id, ledger_id, period_code, start_date, end_date, status)
                        values (?, ?, ?, ?, ?, 'OPEN')
                        """, periodId, ledgerId, month.toString(), month.atDay(1), month.atEndOfMonth());
            }
            periods.add(new Period(periodId, month.toString(), month));
        }
        return periods;
    }

    private void seedPostedVouchers(UUID actorId, UUID ledgerId, List<Period> periods,
                                    UUID cashAccountId, UUID capitalAccountId, int vouchersPerPeriod) {
        for (int periodIndex = 0; periodIndex < periods.size(); periodIndex++) {
            Period period = periods.get(periodIndex);
            for (int voucherIndex = 0; voucherIndex < vouchersPerPeriod; voucherIndex++) {
                vouchers.create(actorId, ledgerId, createRequest(period, cashAccountId, capitalAccountId,
                        "seed-" + periodIndex + "-" + voucherIndex, BigDecimal.valueOf(100 + voucherIndex % 50)));
            }
        }
    }

    private List<MeasuredVoucher> measureCreatedVouchers(
            UUID actorId, UUID ledgerId, Period period, UUID cashAccountId, UUID capitalAccountId,
            BenchmarkConfig config) {
        List<MeasuredVoucher> measurements = new ArrayList<>();
        for (int index = 0; index < config.warmups() + config.iterations(); index++) {
            BigDecimal amount = BigDecimal.valueOf(1_000 + index);
            long started = System.nanoTime();
            VoucherResponses.Voucher voucher = vouchers.create(actorId, ledgerId,
                    createRequest(period, cashAccountId, capitalAccountId, "measured-" + index, amount));
            long transactionMilliseconds = elapsedMillis(started);
            long workerCatchUpMilliseconds = drainProjection();
            measurements.add(new MeasuredVoucher(voucher, index >= config.warmups(), transactionMilliseconds,
                    workerCatchUpMilliseconds));
        }
        return measurements;
    }

    private WriteMetrics measureUpdates(UUID actorId, UUID ledgerId, Period period, UUID cashAccountId,
                                        UUID capitalAccountId, List<MeasuredVoucher> created) {
        List<MetricSample<Void>> transactionSamples = new ArrayList<>();
        List<MetricSample<Void>> workerCatchUpSamples = new ArrayList<>();
        for (MeasuredVoucher createdVoucher : created) {
            VoucherResponses.Voucher voucher = createdVoucher.voucher();
            long started = System.nanoTime();
            vouchers.update(actorId, ledgerId, voucher.id(), new VoucherRequests.Update(
                    voucher.version(), period.id(), voucher.voucherDate(), voucher.voucherType(),
                    voucher.voucherNumber(), "updated", lines(cashAccountId, capitalAccountId,
                    voucher.lines().getFirst().originalAmount().add(BigDecimal.ONE))));
            long transactionMilliseconds = elapsedMillis(started);
            long workerCatchUpMilliseconds = drainProjection();
            if (createdVoucher.sampled()) {
                transactionSamples.add(new MetricSample<>(transactionMilliseconds, null));
                workerCatchUpSamples.add(new MetricSample<>(workerCatchUpMilliseconds, null));
            }
        }
        return new WriteMetrics(metric("posted-update-transaction", transactionSamples),
                metric("posted-update-worker-catch-up", workerCatchUpSamples));
    }

    private List<MetricSample<Void>> samples(List<MeasuredVoucher> measurements,
                                               java.util.function.ToLongFunction<MeasuredVoucher> milliseconds) {
        return measurements.stream()
                .filter(MeasuredVoucher::sampled)
                .map(measurement -> new MetricSample<Void>(milliseconds.applyAsLong(measurement), null))
                .toList();
    }

    private Metric measure(String name, BenchmarkConfig config, Supplier<?> operation) {
        List<MetricSample<Void>> samples = new ArrayList<>();
        for (int index = 0; index < config.warmups() + config.iterations(); index++) {
            long started = System.nanoTime();
            operation.get();
            if (index >= config.warmups()) {
                samples.add(new MetricSample<>(elapsedMillis(started), null));
            }
        }
        return metric(name, samples);
    }

    private <T> Metric metric(String name, List<MetricSample<T>> samples) {
        List<Long> values = samples.stream().map(MetricSample::milliseconds).sorted().toList();
        long total = values.stream().mapToLong(Long::longValue).sum();
        return new Metric(name, values.size(), percentile(values, 0.50), percentile(values, 0.95),
                percentile(values, 0.99), values.getFirst(), values.getLast(), total / (double) values.size());
    }

    private VoucherRequests.Create createRequest(Period period, UUID cashAccountId, UUID capitalAccountId,
                                                 String voucherNumber, BigDecimal amount) {
        return new VoucherRequests.Create(period.id(), period.month().atDay(15), "GENERAL", voucherNumber,
                "projection benchmark", lines(cashAccountId, capitalAccountId, amount));
    }

    private List<VoucherRequests.Line> lines(UUID cashAccountId, UUID capitalAccountId, BigDecimal amount) {
        return List.of(new VoucherRequests.Line(cashAccountId, "DEBIT", "CNY", amount, BigDecimal.ONE,
                        "projection benchmark"),
                new VoucherRequests.Line(capitalAccountId, "CREDIT", "CNY", amount, BigDecimal.ONE,
                        "projection benchmark"));
    }

    private void assertProjectionReady(UUID ledgerId, UUID periodId) {
        Long pending = jdbc.queryForObject("""
                select count(*) from balance_projection_state
                where ledger_id = ? and period_id = ?
                  and (status <> 'READY' or last_enqueued_event_id <> last_applied_event_id)
                """, Long.class, ledgerId, periodId);
        assertThat(pending).isZero();
    }

    private FutureProjection futureProjection(UUID ledgerId, String targetPeriodCode) {
        return jdbc.queryForObject("""
                select count(*), coalesce(sum(
                    abs(b.opening_debit_base - b.opening_credit_base)
                    + abs(b.closing_debit_base - b.closing_credit_base)), 0)
                from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code > ?
                """, (rs, row) -> new FutureProjection(rs.getLong(1), rs.getBigDecimal(2)), ledgerId, targetPeriodCode);
    }

    private long drainProjection() {
        long started = System.nanoTime();
        while (projection.applyPendingBatch(200, 5000)) {
            // Each pass locks and fully catches up one ledger.
        }
        return elapsedMillis(started);
    }

    private UUID accountId(UUID ledgerId, String code) {
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and code = ?", UUID.class,
                ledgerId, code);
    }

    private UUID periodId(UUID ledgerId, String code) {
        return jdbc.queryForObject("select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, code);
    }

    private UUID existingPeriodId(UUID ledgerId, String code) {
        List<UUID> ids = jdbc.query("select id from accounting_period where ledger_id = ? and period_code = ?",
                (rs, row) -> rs.getObject(1, UUID.class), ledgerId, code);
        return ids.isEmpty() ? null : ids.getFirst();
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }

    private long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }

    private record Period(UUID id, String code, YearMonth month) {
    }

    private record MetricSample<T>(long milliseconds, T value) {
    }

    private record MeasuredVoucher(VoucherResponses.Voucher voucher, boolean sampled, long transactionMilliseconds,
                                   long workerCatchUpMilliseconds) {
    }

    private record WriteMetrics(Metric transaction, Metric workerCatchUp) {
    }

    private record FutureProjection(long rows, BigDecimal net) {
    }

    private record Metric(String name, int samples, long p50Ms, long p95Ms, long p99Ms,
                          long minMs, long maxMs, double meanMs) {
        String toJson() {
            return "{\"name\":\"" + name + "\",\"samples\":" + samples + ",\"p50Ms\":" + p50Ms
                    + ",\"p95Ms\":" + p95Ms + ",\"p99Ms\":" + p99Ms + ",\"minMs\":" + minMs
                    + ",\"maxMs\":" + maxMs + ",\"meanMs\":" + String.format(Locale.ROOT, "%.2f", meanMs)
                    + "}";
        }
    }

    private record BenchmarkConfig(int periods, int vouchersPerPeriod, int warmups, int iterations) {
        static BenchmarkConfig fromEnvironment() {
            int periods = positiveInt("BENCHMARK_PERIODS", 20);
            if (periods < TARGET_PERIOD_INDEX + 11) {
                throw new IllegalArgumentException("BENCHMARK_PERIODS must include ten periods after period ten");
            }
            return new BenchmarkConfig(periods, positiveInt("BENCHMARK_VOUCHERS_PER_PERIOD", 500),
                    positiveInt("BENCHMARK_WARMUPS", 5), positiveInt("BENCHMARK_ITERATIONS", 30));
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
