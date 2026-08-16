package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.audit.AuditResponses;
import com.example.accounting.audit.AuditService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.UUID;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Proxy;
import javax.sql.DataSource;
import com.example.accounting.voucher.internal.persistence.JdbcVoucherRepository;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class Stage4BackendPerformanceIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private AuditService audits;

    @Autowired
    private ReportingService reports;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BalanceProjectionRepository projection;

    @Autowired
    private DataSource dataSource;

    @Test
    void auditCursorPagesWithoutDuplicatesAndSupportsAggregateFilter() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID aggregate = UUID.randomUUID();
        for (int i = 0; i < 120; i++) {
            jdbc.update("""
                    insert into audit_revision (id, ledger_id, aggregate_type, aggregate_id, revision, action,
                        actor_type, actor_id, reason, created_at)
                    values (?, ?, ?, ?, ?, 'CREATE', 'USER', ?, null, ?)
                    """, UUID.randomUUID(), ledger, i % 2 == 0 ? "VOUCHER" : "ACCOUNT", aggregate,
                    i + 1, actor, OffsetDateTime.now().minusSeconds(i));
        }

        Set<UUID> ids = new HashSet<>();
        String cursor = null;
        int pages = 0;
        boolean hasMore;
        do {
            AuditResponses.Page page = audits.page(actor, ledger, 50, cursor, null, aggregate);
            page.items().forEach(item -> assertThat(ids.add(item.id())).isTrue());
            cursor = page.nextCursor();
            hasMore = page.hasMore();
            pages++;
        } while (hasMore);
        assertThat(ids).hasSize(120);
        assertThat(pages).isEqualTo(3);
        assertThat(audits.page(actor, ledger, 200, null, "VOUCHER", aggregate).items()).hasSize(60);
        assertThatThrownBy(() -> audits.page(actor, ledger, 50, "bad", null, null))
                .isInstanceOf(com.example.accounting.shared.web.ApiProblemException.class);
    }

    @Test
    void cleanupDeletesOnlyAppliedEventsAndNeverFailedOrUnappliedEvents() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID readyPeriod = period(ledger, "2026-01");
        UUID failedPeriod = period(ledger, "2026-02");
        UUID safeAggregate = UUID.randomUUID();
        UUID unsafeAggregate = UUID.randomUUID();
        UUID failedAggregate = UUID.randomUUID();
        long safeId = event(ledger, readyPeriod, safeAggregate);
        long unsafeId = event(ledger, readyPeriod, unsafeAggregate);
        long failedId = event(ledger, failedPeriod, failedAggregate);
        jdbc.update("""
                update balance_projection_state set status = 'READY', last_applied_event_id = ?,
                    last_enqueued_event_id = ?, updated_at = now()
                where ledger_id = ? and period_id = ?
                """, safeId, unsafeId, ledger, readyPeriod);
        jdbc.update("""
                update balance_projection_state set status = 'FAILED', last_applied_event_id = ?,
                    last_enqueued_event_id = ?, updated_at = now()
                where ledger_id = ? and period_id = ?
                """, failedId, failedId, ledger, failedPeriod);
        jdbc.update("update balance_projection_event set created_at = now() - interval '2 days' where id in (?, ?, ?)",
                safeId, unsafeId, failedId);

        // Exercise the public cleanup path through the injected repository implementation.
        com.example.accounting.reporting.internal.port.BalanceProjectionRepository repository =
                findProjectionRepository();
        int removed = repository.cleanupAppliedEvents(OffsetDateTime.now(), 1000);

        // Other tests may leave eligible events in the shared integration schema; this assertion
        // deliberately covers the safety boundary instead of assuming it is the only candidate.
        assertThat(removed).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from balance_projection_event where id = ?", Integer.class, safeId))
                .isZero();
        assertThat(jdbc.queryForObject("select count(*) from balance_projection_event where id = ?", Integer.class, unsafeId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from balance_projection_event where id = ?", Integer.class, failedId))
                .isEqualTo(1);
    }

    @Test
    void projectionBatchProcessesTwoPeriodsAndReportsRemainingDirtyPeriods() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID jan = period(ledger, "2026-01");
        long eventId = event(ledger, jan, UUID.randomUUID());
        jdbc.update("""
                update balance_projection_state
                   set status = 'READY', last_enqueued_event_id = ?, last_enqueued_at = now() - interval '100 days',
                       last_applied_event_id = null, updated_at = now()
                 where ledger_id = ?
                """, eventId, ledger);

        BalanceProjectionRepository.BatchResult first = projection.applyPendingBatchDetailed(2, false);
        assertThat(first.processed()).isTrue();
        assertThat(first.processedPeriods()).isEqualTo(2);
        BalanceProjectionRepository.ProjectionMetrics metrics = projection.projectionMetrics();
        assertThat(metrics.remainingDirtyPeriods()).isGreaterThanOrEqualTo(1);
        assertThat(metrics.oldestPendingAt()).isNotNull();

        // A newer event in an earlier period must be picked up before later periods.
        long earlierEvent = event(ledger, jan, UUID.randomUUID());
        jdbc.update("""
                update balance_projection_state
                   set last_enqueued_event_id = ?, last_enqueued_at = now() - interval '99 days', status = 'READY',
                       updated_at = now()
                 where ledger_id = ? and period_id = ?
                """, earlierEvent, ledger, jan);
        BalanceProjectionRepository.BatchResult second = projection.applyPendingBatchDetailed(5, false);
        assertThat(second.processed()).isTrue();
        assertThat(second.processedPeriods()).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(5);
        assertThat(jdbc.queryForObject("""
                select last_applied_event_id from balance_projection_state
                 where ledger_id = ? and period_id = ?
                """, Long.class, ledger, jan)).isEqualTo(earlierEvent);
    }

    @Test
    void projectionBatchRollsBackAccountsDimensionsAndWatermarksWhenDimensionRebuildFails() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID january = period(ledger, "2026-01");
        LedgerResponses.DimensionType customer = ledgers.listDimensionTypes(actor, ledger).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        UUID receivable = ledgers.createAccount(actor, ledger,
                new LedgerRequests.AccountCreate("1410", "stage4 receivable", "ASSET.ACCOUNTS_RECEIVABLE",
                        "CURRENT_ASSET", "DEBIT", null, false, null, false, null,
                        List.of(new LedgerRequests.DimensionRequirement(customer.id(), true)))).id();
        LedgerResponses.DimensionValue customerA = ledgers.createDimensionValue(actor, ledger, customer.id(),
                new LedgerRequests.DimensionValueCreate("stage4-customer", "Stage4 customer"));
        UUID capital = account(ledger, "3001");
        vouchers.create(actor, ledger, new VoucherRequests.Create(january, LocalDate.of(2026, 1, 10),
                "GENERAL", "dimension-rollback", "dimension rollback", List.of(
                new VoucherRequests.Line(receivable, "DEBIT", "CNY", BigDecimal.TEN, BigDecimal.ONE, "dimension",
                        null, null, null, List.of(new VoucherRequests.Dimension(customer.id(), customerA.id()))),
                new VoucherRequests.Line(capital, "CREDIT", "CNY", BigDecimal.TEN, BigDecimal.ONE, "dimension"))));
        Long enqueued = jdbc.queryForObject("""
                select last_enqueued_event_id from balance_projection_state
                where ledger_id = ? and period_id = ?
                """, Long.class, ledger, january);
        assertThat(enqueued).isNotNull();
        jdbc.update("""
                update balance_projection_state set last_enqueued_at = now() - interval '200 days'
                where ledger_id = ? and period_id = ?
                """, ledger, january);
        jdbc.execute("""
                create function stage4_reject_dimension_balance() returns trigger language plpgsql as $$
                begin raise exception 'stage4 dimension rebuild failure'; end $$
                """);
        jdbc.execute("""
                create trigger stage4_reject_dimension_balance before insert on dimension_period_balance
                for each row execute function stage4_reject_dimension_balance()
                """);
        try {
            assertThatThrownBy(() -> projection.applyPendingBatchDetailed(2, false))
                    .hasMessageContaining("stage4 dimension rebuild failure");
            assertThat(jdbc.queryForObject("select count(*) from account_period_balance where ledger_id = ?",
                    Long.class, ledger)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from dimension_period_balance where ledger_id = ?",
                    Long.class, ledger)).isZero();
            assertThat(jdbc.queryForObject("""
                    select last_applied_event_id from balance_projection_state
                    where ledger_id = ? and period_id = ?
                    """, Long.class, ledger, january)).isNotEqualTo(enqueued);
        } finally {
            jdbc.execute("drop trigger if exists stage4_reject_dimension_balance on dimension_period_balance");
            jdbc.execute("drop function if exists stage4_reject_dimension_balance()");
        }

        BalanceProjectionRepository.BatchResult recovered = projection.applyPendingBatchDetailed(2, false);
        assertThat(recovered.processed()).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from account_period_balance where ledger_id = ?",
                Long.class, ledger)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("select count(*) from dimension_period_balance where ledger_id = ?",
                Long.class, ledger)).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                select last_applied_event_id from balance_projection_state
                where ledger_id = ? and period_id = ?
                """, Long.class, ledger, january)).isEqualTo(enqueued);
    }

    @Test
    void deepEmptyDetailLedgerPagePreservesWindowMetadata() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID account = jdbc.queryForObject("""
                select account.id from ledger_account account
                where account.ledger_id = ? and not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                order by account.code limit 1
                """, UUID.class, ledger);
        UUID offsetAccount = jdbc.queryForObject("""
                select account.id from ledger_account account
                where account.ledger_id = ? and not exists (
                    select 1 from ledger_account child
                    where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                  and account.id <> ?
                order by account.code limit 1
                """, UUID.class, ledger, account);
        vouchers.create(actor, ledger, new VoucherRequests.Create(period(ledger, "2026-01"),
                LocalDate.of(2026, 1, 15), "GENERAL", "deep-page", "deep page",
                java.util.List.of(
                        new VoucherRequests.Line(account, "DEBIT", "CNY", BigDecimal.TEN, BigDecimal.ONE, "deep"),
                        new VoucherRequests.Line(offsetAccount, "CREDIT", "CNY", BigDecimal.TEN, BigDecimal.ONE, "deep"))));
        var page = reports.subLedgerBook(actor, ledger, "2026-01", account, 999, 10);
        assertThat(page.data()).isEmpty();
        assertThat(page.pagination().totalItems()).isEqualTo(1);
        assertThat(page.periodDebit()).isEqualByComparingTo(BigDecimal.TEN);
        assertThat(page.periodCredit()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void subLedgerCheckpointIsInvalidatedByPostedVoucherLines() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID target = jdbc.queryForObject("""
                select account.id from ledger_account account where account.ledger_id = ?
                  and account.normal_balance = 'DEBIT' and not exists (select 1 from ledger_account child
                      where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                order by account.code limit 1
                """, UUID.class, ledger);
        UUID counterpart = jdbc.queryForObject("""
                select account.id from ledger_account account where account.ledger_id = ?
                  and account.normal_balance = 'CREDIT' and not exists (select 1 from ledger_account child
                      where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                order by account.code limit 1
                """, UUID.class, ledger);
        UUID period = period(ledger, "2026-01");
        vouchers.create(actor, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10), "GENERAL", "cp-1",
                "checkpoint", List.of(new VoucherRequests.Line(target, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"),
                        new VoucherRequests.Line(counterpart, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"))));
        assertThat(reports.subLedgerBook(actor, ledger, "2026-01", target, 1, 50).pagination().totalItems()).isEqualTo(1);
        vouchers.create(actor, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 11), "GENERAL", "cp-2",
                "checkpoint", List.of(new VoucherRequests.Line(target, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"),
                        new VoucherRequests.Line(counterpart, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"))));
        var rebuilt = reports.subLedgerBook(actor, ledger, "2026-01", target, 1, 50);
        assertThat(rebuilt.pagination().totalItems()).isEqualTo(2);
        assertThat(rebuilt.periodDebit()).isEqualByComparingTo("2.00");
        UUID movedLine = jdbc.queryForObject("select id from voucher_line where ledger_id = ? and account_id = ? order by line_no limit 1",
                UUID.class, ledger, target);
        jdbc.update("update voucher_line set account_id = ? where id = ?", counterpart, movedLine);
        assertThat(reports.subLedgerBook(actor, ledger, "2026-01", target, 1, 50)
                .pagination().totalItems()).isEqualTo(1);
    }

    @Test
    void subLedgerCheckpointIsInvalidatedByVoucherSummaryChanges() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID period = period(ledger, "2026-01");
        UUID target = account(ledger, "1001");
        UUID counterpart = account(ledger, "3001");
        var voucher = vouchers.create(actor, ledger, new VoucherRequests.Create(
                period, LocalDate.of(2026, 1, 12), "GENERAL", "cp-summary", "before",
                List.of(new VoucherRequests.Line(target, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, null),
                        new VoucherRequests.Line(counterpart, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, null))));

        assertThat(reports.subLedgerBook(actor, ledger, "2026-01", target, 1, 50)
                .data().getFirst().summary()).isEqualTo("before");

        jdbc.update("update voucher set summary = 'after' where ledger_id = ? and id = ?", ledger, voucher.id());

        assertThat(reports.subLedgerBook(actor, ledger, "2026-01", target, 1, 50)
                .data().getFirst().summary()).isEqualTo("after");
    }

    @Test
    void concurrentVoucherWriteLeavesCheckpointDirtyUntilTheNextConsistentRead() throws Exception {
        UUID actor = UUID.randomUUID(), ledger = createLedger(actor), period = period(ledger, "2026-01");
        UUID debit = account(ledger, "1001"), credit = account(ledger, "3001");
        createCheckpointVoucher(actor, ledger, period, debit, credit, "epoch-1");
        reports.subLedgerBook(actor, ledger, "2026-01", debit, 1, 50);
        jdbc.execute("create function stage4_checkpoint_pause() returns trigger language plpgsql as $$ begin perform pg_sleep(.4); return new; end $$");
        jdbc.execute("create trigger stage4_checkpoint_pause_trigger before insert on sub_ledger_checkpoint for each row execute function stage4_checkpoint_pause()");
        createCheckpointVoucher(actor, ledger, period, debit, credit, "epoch-2");
        CompletableFuture<Void> rebuilding = CompletableFuture.runAsync(() ->
                reports.subLedgerBook(actor, ledger, "2026-01", debit, 1, 50));
        Thread.sleep(100);
        CompletableFuture<Void> writer = CompletableFuture.runAsync(() ->
                createCheckpointVoucher(actor, ledger, period, debit, credit, "epoch-3"));
        rebuilding.get(10, TimeUnit.SECONDS);
        writer.get(10, TimeUnit.SECONDS);
        jdbc.execute("drop trigger stage4_checkpoint_pause_trigger on sub_ledger_checkpoint");
        jdbc.execute("drop function stage4_checkpoint_pause()");
        assertThat(reports.subLedgerBook(actor, ledger, "2026-01", debit, 1, 50)
                .pagination().totalItems()).isEqualTo(3);
    }

    private void createCheckpointVoucher(UUID actor, UUID ledger, UUID period, UUID debit, UUID credit, String number) {
        vouchers.create(actor, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 12), "GENERAL", number,
                "checkpoint", List.of(new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"),
                        new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "cp"))));
    }

    @Test
    void voucherServiceAcceptsFiveHundredLinesAndRollsBackInvalidBatch() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID period = period(ledger, "2026-01");
        UUID debit = account(ledger, "1001");
        UUID credit = account(ledger, "3001");
        List<VoucherRequests.Line> lines = new ArrayList<>(500);
        for (int i = 0; i < 250; i++) {
            lines.add(new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "bulk"));
            lines.add(new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "bulk"));
        }
        long before = jdbc.queryForObject("select count(*) from voucher_line where ledger_id = ?", Long.class, ledger);
        var created = vouchers.create(actor, ledger, new VoucherRequests.Create(period,
                LocalDate.of(2026, 1, 15), "GENERAL", "bulk-500", "bulk", lines));
        assertThat(jdbc.queryForObject("select count(*) from voucher_line where voucher_id = ?", Long.class,
                created.id())).isEqualTo(500);
        assertThat(jdbc.queryForObject("select count(*) from voucher_line where ledger_id = ?", Long.class, ledger))
                .isEqualTo(before + 500);

        List<VoucherRequests.Line> invalid = new ArrayList<>(lines);
        invalid.set(249, new VoucherRequests.Line(UUID.randomUUID(), "DEBIT", "CNY", BigDecimal.ONE,
                BigDecimal.ONE, "invalid"));
        long vouchersBeforeFailure = jdbc.queryForObject("select count(*) from voucher where ledger_id = ?",
                Long.class, ledger);
        assertThatThrownBy(() -> vouchers.create(actor, ledger, new VoucherRequests.Create(period,
                LocalDate.of(2026, 1, 16), "GENERAL", "bulk-invalid", "invalid", invalid)))
                .isInstanceOf(com.example.accounting.shared.web.ApiProblemException.class);
        assertThat(jdbc.queryForObject("select count(*) from voucher where ledger_id = ?", Long.class, ledger))
                .isEqualTo(vouchersBeforeFailure);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_VOUCHER_BENCHMARK", matches = "true")
    void voucherBatchBenchmarkMeasuresDriverExecutionsAgainstLegacyLineWrites() throws Exception {
        CountingDataSource counted = new CountingDataSource(dataSource);
        JdbcVoucherRepository repository = new JdbcVoucherRepository(new JdbcTemplate(counted));
        List<Long> legacyNanos = new ArrayList<>();
        List<Long> batchNanos = new ArrayList<>();
        long legacyExecutes = 0, batchExecutes = 0, batchCalls = 0;
        for (int run = 0; run < 5; run++) {
            UUID actor = UUID.randomUUID(); UUID ledger = createLedger(actor); UUID period = period(ledger, "2026-01");
            UUID debit = account(ledger, "1001"), credit = account(ledger, "3001");
            var header = vouchers.create(actor, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 12),
                    "GENERAL", "legacy-" + run, "benchmark", List.of(
                    new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "seed"),
                    new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "seed"))));
            List<VoucherRepository.LineInsert> lines = benchmarkLines(ledger, header.id(), debit, credit);
            counted.reset(); long start = System.nanoTime();
            for (VoucherRepository.LineInsert line : lines) { repository.createLine(line.lineId(), line.ledgerId(),
                    line.voucherId(), line.lineNo(), line.accountId(), line.side(), line.currency(), line.originalAmount(),
                    line.exchangeRate(), line.baseAmount(), line.summary(), null, null, null, line.dimensionCombinationId()); }
            legacyNanos.add(System.nanoTime() - start); legacyExecutes += counted.executes.get();
            var batchHeader = vouchers.create(actor, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 13),
                    "GENERAL", "batch-" + run, "benchmark", List.of(
                    new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "seed"),
                    new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE, "seed"))));
            counted.reset(); start = System.nanoTime(); repository.createLines(benchmarkLines(ledger, batchHeader.id(), debit, credit));
            batchNanos.add(System.nanoTime() - start); batchExecutes += counted.executes.get(); batchCalls += counted.batches.get();
        }
        assertThat(batchExecutes).isLessThanOrEqualTo(legacyExecutes / 5);
        Path artifact = Path.of("artifacts", "performance", "stage4-voucher-batch-500.json"); Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"runs\":5,\"legacyExecuteCalls\":" + legacyExecutes
                + ",\"batchExecuteCalls\":" + batchExecutes + ",\"batchCalls\":" + batchCalls
                + ",\"legacyP50Ms\":" + percentileNanos(legacyNanos,.5) + ",\"legacyP95Ms\":" + percentileNanos(legacyNanos,.95)
                + ",\"batchP50Ms\":" + percentileNanos(batchNanos,.5) + ",\"batchP95Ms\":" + percentileNanos(batchNanos,.95) + "}");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_PROJECTION_BENCHMARK", matches = "true")
    void projectionBatchBenchmarkMatchesLegacyFiveDirtyPeriods() throws Exception {
        List<Long> legacyNanos = new ArrayList<>(), batchNanos = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            UUID actor = UUID.randomUUID();
            UUID legacyLedger = fiveDirtyPeriodFixture(actor, 1000 + run * 10);
            long start = System.nanoTime();
            BalanceProjectionRepository.BatchResult legacy = projection.applyPendingBatchDetailed(Integer.MAX_VALUE, true);
            legacyNanos.add(System.nanoTime() - start); assertThat(legacy.processedPeriods()).isGreaterThanOrEqualTo(5);
            UUID batchLedger = fiveDirtyPeriodFixture(actor, 900 + run * 10);
            List<Integer> periods = new ArrayList<>();
            while (true) { start = System.nanoTime(); BalanceProjectionRepository.BatchResult batch = projection.applyPendingBatchDetailed(2, false);
                if (!batch.processed()) break; batchNanos.add(System.nanoTime() - start); periods.add(batch.processedPeriods()); }
            assertThat(periods).containsExactly(2, 2, 1);
            assertThat(checksum(legacyLedger)).isEqualTo(checksum(batchLedger));
            assertThat(jdbc.queryForObject("""
                    select count(*) from balance_projection_state s join accounting_period p on p.id=s.period_id
                    where s.ledger_id=? and p.period_code between '2026-01' and '2026-05'
                      and coalesce(s.last_applied_event_id,0) < coalesce(s.last_enqueued_event_id,0)
                    """, Long.class, batchLedger)).isZero();
        }
        Path artifact = Path.of("artifacts", "performance", "stage4-projection-batch-5-periods.json"); Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"runs\":5,\"metric\":\"applyPendingBatchDetailed transaction duration proxy, not database lock wait\",\"legacyP50Ms\":"
                + percentileNanos(legacyNanos,.5) + ",\"legacyP95Ms\":" + percentileNanos(legacyNanos,.95)
                + ",\"batchP50Ms\":" + percentileNanos(batchNanos,.5) + ",\"batchP95Ms\":" + percentileNanos(batchNanos,.95) + "}");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_PROJECTION_BENCHMARK", matches = "true")
    void projectionLongTailBenchmarkMeasuresHeavyFivePeriodWorkload() throws Exception {
        List<Long> legacy = new ArrayList<>(), batch = new ArrayList<>();
        for (int run = 0; run < 5; run++) {
            UUID actor = UUID.randomUUID(); UUID oldLedger = fiveDirtyPeriodFixture(actor, 2000 + run * 10, 50);
            long start = System.nanoTime(); projection.applyPendingBatchDetailed(Integer.MAX_VALUE, true); legacy.add(System.nanoTime()-start);
            UUID newLedger = fiveDirtyPeriodFixture(actor, 1900 + run * 10, 50);
            while (true) { start = System.nanoTime(); var result = projection.applyPendingBatchDetailed(2, false);
                if (!result.processed()) break; batch.add(System.nanoTime()-start); }
            assertThat(checksum(oldLedger)).isEqualTo(checksum(newLedger));
        }
        Path artifact = Path.of("artifacts", "performance", "stage4-projection-batch-long-tail.json"); Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"runs\":5,\"periods\":5,\"voucherLinesPerPeriod\":100,\"metric\":\"transaction duration proxy, not database lock wait\",\"legacyP50Ms\":"+percentileNanos(legacy,.5)+",\"legacyP95Ms\":"+percentileNanos(legacy,.95)+",\"batchP50Ms\":"+percentileNanos(batch,.5)+",\"batchP95Ms\":"+percentileNanos(batch,.95)+"}");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_CLEANUP_BENCHMARK", matches = "true")
    void cleanupBenchmarkDeletesAtMostOneThousandAppliedEventsAndRetainsUnsafeStates() throws Exception {
        List<Long> durations = new ArrayList<>();
        for (int run = 0; run < 10; run++) {
            UUID actor = UUID.randomUUID(), ledger = createLedger(actor); UUID safe = period(ledger, "2026-01");
            UUID failed = period(ledger, "2026-02"); List<Object[]> rows = new ArrayList<>();
            for (int i=0;i<1000;i++) rows.add(new Object[] {ledger, safe, UUID.randomUUID()});
            jdbc.batchUpdate("insert into balance_projection_event (ledger_id,period_id,aggregate_type,aggregate_id,aggregate_version,event_type,created_at)"
                    + " values (?,?,'VOUCHER',?,1,'POST',now()-interval '2 days')", rows);
            long max = jdbc.queryForObject("select max(id) from balance_projection_event where ledger_id=? and period_id=?", Long.class, ledger, safe);
            jdbc.update("update balance_projection_state set last_enqueued_event_id=?,last_applied_event_id=?,status='READY' where ledger_id=? and period_id=?", max,max,ledger,safe);
            long failedId = event(ledger, failed, UUID.randomUUID()); jdbc.update("update balance_projection_state set last_enqueued_event_id=?,last_applied_event_id=?,status='FAILED' where ledger_id=? and period_id=?", failedId,failedId,ledger,failed);
            long start=System.nanoTime(); int removed=projection.cleanupAppliedEvents(OffsetDateTime.now(),1000); durations.add(System.nanoTime()-start);
            assertThat(removed).isEqualTo(1000); assertThat(jdbc.queryForObject("select count(*) from balance_projection_event where ledger_id=? and period_id=?",Long.class,ledger,failed)).isEqualTo(1);
        }
        Path artifact=Path.of("artifacts","performance","stage4-cleanup-1000.json"); Files.createDirectories(artifact.getParent());
        Files.writeString(artifact,"{\"runs\":10,\"batchSize\":1000,\"p50Ms\":"+percentileNanos(durations,.5)+",\"p95Ms\":"+percentileNanos(durations,.95)+",\"unsafeStatesRetained\":true}");
    }

    private UUID fiveDirtyPeriodFixture(UUID actor, int ageDays) {
        return fiveDirtyPeriodFixture(actor, ageDays, 1);
    }

    private UUID fiveDirtyPeriodFixture(UUID actor, int ageDays, int pairs) {
        UUID ledger = createLedger(actor); UUID debit = account(ledger, "1001"), credit = account(ledger, "3001");
        for (int month = 1; month <= 5; month++) { String code = "2026-0" + month; vouchers.create(actor, ledger,
                new VoucherRequests.Create(period(ledger, code), LocalDate.of(2026, month, 10), "GENERAL", "p" + month,
                        "projection benchmark", projectionLines(debit, credit, month, pairs))); }
        jdbc.update("update balance_projection_state set last_enqueued_at = now() - (? * interval '1 day')"
                + " where ledger_id=? and period_id in (select id from accounting_period where ledger_id=? and period_code between '2026-01' and '2026-05')", ageDays, ledger, ledger);
        jdbc.update("""
                update balance_projection_state set last_applied_event_id = last_enqueued_event_id
                where ledger_id = ? and period_id in (
                    select id from accounting_period where ledger_id = ? and period_code > '2026-05')
                """, ledger, ledger);
        return ledger;
    }

    private List<VoucherRequests.Line> projectionLines(UUID debit, UUID credit, int month, int pairs) {
        List<VoucherRequests.Line> result = new ArrayList<>(); for (int i=0;i<pairs;i++) { result.add(new VoucherRequests.Line(debit, "DEBIT", "CNY", BigDecimal.valueOf(month), BigDecimal.ONE, "p")); result.add(new VoucherRequests.Line(credit, "CREDIT", "CNY", BigDecimal.valueOf(month), BigDecimal.ONE, "p")); } return result;
    }

    private String checksum(UUID ledger) throws Exception {
        String accounts = jdbc.queryForObject("select coalesce(string_agg(x, E'\\n' order by x),'') from ("
                + " select p.period_code||'|'||a.code||'|'||b.opening_debit_base||'|'||b.opening_credit_base||'|'||b.period_debit_base||'|'||b.period_credit_base||'|'||b.closing_debit_base x"
                + " from account_period_balance b join accounting_period p on p.id=b.period_id join ledger_account a on a.id=b.account_id where b.ledger_id=? and p.period_code between '2026-01' and '2026-05') q", String.class, ledger);
        String dimensions = jdbc.queryForObject("select coalesce(string_agg(p.period_code||'|'||a.code||'|'||c.canonical_key||'|'||b.currency||'|'||b.closing_debit_base, E'\\n' order by p.period_code,a.code,c.canonical_key,b.currency),'') from dimension_period_balance b join accounting_period p on p.id=b.period_id join ledger_account a on a.id=b.account_id join dimension_combination c on c.id=b.dimension_combination_id where b.ledger_id=? and p.period_code between '2026-01' and '2026-05'", String.class, ledger);
        return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((accounts + "\\n" + dimensions).getBytes(StandardCharsets.UTF_8)));
    }

    private List<VoucherRepository.LineInsert> benchmarkLines(UUID ledger, UUID voucher, UUID debit, UUID credit) {
        List<VoucherRepository.LineInsert> lines = new ArrayList<>();
        for (int i = 0; i < 500; i++) lines.add(new VoucherRepository.LineInsert(UUID.randomUUID(), ledger, voucher, i + 3,
                i % 2 == 0 ? debit : credit, i % 2 == 0 ? "DEBIT" : "CREDIT", "CNY", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, "benchmark", null, null, null, null));
        return lines;
    }

    private long percentileNanos(List<Long> values, double p) { values.sort(Long::compareTo); return values.get((int) Math.ceil(p * values.size()) - 1) / 1_000_000; }

    private static final class CountingDataSource implements DataSource {
        private final DataSource delegate; final AtomicLong executes = new AtomicLong(), batches = new AtomicLong();
        CountingDataSource(DataSource delegate) { this.delegate = delegate; }
        void reset() { executes.set(0); batches.set(0); }
        public java.sql.Connection getConnection() throws java.sql.SQLException { return connection(delegate.getConnection()); }
        public java.sql.Connection getConnection(String u, String p) throws java.sql.SQLException { return connection(delegate.getConnection(u,p)); }
        private java.sql.Connection connection(java.sql.Connection c) { return (java.sql.Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {java.sql.Connection.class}, (p,m,a) -> {
            Object v = m.invoke(c,a); if (m.getName().startsWith("prepare") && v instanceof java.sql.PreparedStatement ps) return statement(ps); return v; }); }
        private java.sql.PreparedStatement statement(java.sql.PreparedStatement s) { return (java.sql.PreparedStatement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[] {java.sql.PreparedStatement.class}, (p,m,a) -> { if (m.getName().equals("executeBatch")) { batches.incrementAndGet(); executes.incrementAndGet(); } else if (m.getName().equals("executeUpdate")) executes.incrementAndGet(); return m.invoke(s,a); }); }
        public <T> T unwrap(Class<T> c) throws java.sql.SQLException { return delegate.unwrap(c); } public boolean isWrapperFor(Class<?> c) throws java.sql.SQLException { return delegate.isWrapperFor(c); }
        public java.io.PrintWriter getLogWriter() throws java.sql.SQLException { return delegate.getLogWriter(); } public void setLogWriter(java.io.PrintWriter w) throws java.sql.SQLException { delegate.setLogWriter(w); }
        public void setLoginTimeout(int n) throws java.sql.SQLException { delegate.setLoginTimeout(n); } public int getLoginTimeout() throws java.sql.SQLException { return delegate.getLoginTimeout(); }
        public java.util.logging.Logger getParentLogger() throws java.sql.SQLFeatureNotSupportedException { return delegate.getParentLogger(); }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_AUDIT_BENCHMARK", matches = "true")
    void auditCursorBenchmarkWithOneHundredThousandRows() throws Exception {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID aggregate = UUID.randomUUID();
        String sql = """
                insert into audit_revision (id, ledger_id, aggregate_type, aggregate_id, revision, action,
                    actor_type, actor_id, reason, created_at)
                values (?, ?, 'VOUCHER', ?, ?, 'CREATE', 'USER', ?, null, ?)
                """;
        List<Object[]> batch = new ArrayList<>(1000);
        OffsetDateTime base = OffsetDateTime.now();
        for (int i = 0; i < 100_000; i++) {
            batch.add(new Object[] {UUID.randomUUID(), ledger, aggregate, i + 1, actor, base.minusSeconds(i)});
            if (batch.size() == 1000) {
                jdbc.batchUpdate(sql, batch);
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            jdbc.batchUpdate(sql, batch);
        }

        Set<UUID> ids = new HashSet<>(100_000);
        List<Long> durations = new ArrayList<>();
        long payloadBytes = 0;
        String cursor = null;
        int pages = 0;
        boolean hasMore;
        long maxPayloadBytes = 0;
        do {
            long started = System.nanoTime();
            AuditResponses.Page page = audits.page(actor, ledger, 200, cursor, "VOUCHER", aggregate);
            durations.add((System.nanoTime() - started) / 1_000_000);
            long pagePayloadBytes = page.items().stream().mapToLong(item ->
                    item.id().toString().length() + item.aggregateType().length()
                            + item.aggregateId().toString().length() + item.action().length()
                            + item.createdAt().toString().length() + 32).sum();
            payloadBytes += pagePayloadBytes;
            maxPayloadBytes = Math.max(maxPayloadBytes, pagePayloadBytes);
            page.items().forEach(item -> assertThat(ids.add(item.id())).isTrue());
            cursor = page.nextCursor();
            hasMore = page.hasMore();
            pages++;
        } while (hasMore);
        assertThat(ids).hasSize(100_000);
        durations.sort(Long::compareTo);
        jdbc.execute("analyze audit_revision");
        String explainBoth = explain("""
                select id from audit_revision
                where ledger_id = ? and aggregate_type = 'VOUCHER' and aggregate_id = ?
                order by created_at desc, id desc limit 201
                """, ledger, aggregate);
        String explainTypeOnly = explain("""
                select id from audit_revision
                where ledger_id = ? and aggregate_type = 'VOUCHER'
                order by created_at desc, id desc limit 201
                """, ledger);
        String explainIdOnly = explain("""
                select id from audit_revision
                where ledger_id = ? and aggregate_id = ?
                order by created_at desc, id desc limit 201
                """, ledger, aggregate);
        String explainNone = explain("""
                select id from audit_revision
                where ledger_id = ?
                order by created_at desc, id desc limit 201
                """, ledger);
        Path artifact = Path.of("artifacts", "performance", "stage4-audit-cursor-100k.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"rows\":100000,\"pages\":" + pages
                + ",\"p50Ms\":" + percentile(durations, 0.50)
                + ",\"p95Ms\":" + percentile(durations, 0.95)
                + ",\"payloadBytesApprox\":" + payloadBytes
                + ",\"maxPagePayloadBytesApprox\":" + maxPayloadBytes
                + ",\"explainMatrix\":{\"both\":\"" + jsonEscape(explainBoth)
                + "\",\"typeOnly\":\"" + jsonEscape(explainTypeOnly)
                + "\",\"idOnly\":\"" + jsonEscape(explainIdOnly)
                + "\",\"none\":\"" + jsonEscape(explainNone) + "\"}}");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RUN_STAGE4_DETAIL_LEDGER_BENCHMARK", matches = "true")
    void detailLedgerOneMillionRowsKeepsDeepPageWithinPerformanceGate() throws Exception {
        DetailLedgerFixture fixture = detailLedgerFixture();
        jdbc.execute("analyze voucher");
        jdbc.execute("analyze voucher_line");

        assertThat(jdbc.queryForObject("select count(*) from voucher_line where ledger_id = ? and account_id = ?",
                Long.class, fixture.ledgerId(), fixture.targetAccountId())).isEqualTo(1_000_000L);

        // Warm the JVM, connection and PostgreSQL buffer cache before collecting the five required samples.
        reports.subLedgerBook(fixture.actorId(), fixture.ledgerId(), "2026-01", fixture.targetAccountId(), 1, 500);
        reports.subLedgerBook(fixture.actorId(), fixture.ledgerId(), "2026-01", fixture.targetAccountId(), 100, 500);
        List<Long> pageOneNanos = new ArrayList<>();
        List<Long> pageOneHundredNanos = new ArrayList<>();
        for (int sample = 0; sample < 5; sample++) {
            pageOneNanos.add(timedSubLedgerPage(fixture, 1));
            pageOneHundredNanos.add(timedSubLedgerPage(fixture, 100));
        }
        var firstPage = reports.subLedgerBook(
                fixture.actorId(), fixture.ledgerId(), "2026-01", fixture.targetAccountId(), 1, 500);
        var deepPage = reports.subLedgerBook(
                fixture.actorId(), fixture.ledgerId(), "2026-01", fixture.targetAccountId(), 100, 500);
        assertThat(firstPage.data()).hasSize(500);
        assertThat(deepPage.data()).hasSize(500);
        assertThat(firstPage.pagination().totalItems()).isEqualTo(1_000_000L);
        assertThat(firstPage.periodDebit()).isEqualByComparingTo("1000000.00");
        assertThat(firstPage.periodCredit()).isEqualByComparingTo(BigDecimal.ZERO);

        String explain = explainDetailLedger(fixture);
        boolean diskSortSpill = Pattern.compile("Sort Method: (?:external|.*Disk)", Pattern.CASE_INSENSITIVE)
                .matcher(explain).find();
        long tempReadBlocks = explainBufferBlocks(explain, "temp read=");
        long tempWrittenBlocks = explainBufferBlocks(explain, "temp written=");
        long pageOneP50 = percentileNanos(pageOneNanos, .50);
        long pageOneP95 = percentileNanos(pageOneNanos, .95);
        long pageOneHundredP50 = percentileNanos(pageOneHundredNanos, .50);
        long pageOneHundredP95 = percentileNanos(pageOneHundredNanos, .95);
        double p95Ratio = pageOneP95 == 0 ? 0 : (double) pageOneHundredP95 / pageOneP95;
        Path artifact = Path.of("artifacts", "performance", "stage4-detail-ledger-1m.json");
        Files.createDirectories(artifact.getParent());
        Files.writeString(artifact, "{\"fixtureRows\":1001000,\"matchedRows\":1000000,\"pageSize\":500,"
                + "\"pages\":[1,100],\"samples\":5,\"page1\":{\"p50Ms\":" + pageOneP50
                + ",\"p95Ms\":" + pageOneP95 + "},\"page100\":{\"p50Ms\":" + pageOneHundredP50
                + ",\"p95Ms\":" + pageOneHundredP95 + "},\"p95Ratio\":" + p95Ratio
                + ",\"explain\":{\"diskSortSpill\":" + diskSortSpill + ",\"tempReadBlocks\":" + tempReadBlocks
                + ",\"tempWrittenBlocks\":" + tempWrittenBlocks + ",\"text\":\"" + jsonEscape(explain) + "\"}}");
        assertThat(diskSortSpill).as("EXPLAIN must not spill detail-ledger sorts to disk").isFalse();
        assertThat(p95Ratio).as("page 100 p95/page 1 p95").isLessThanOrEqualTo(1.20);
    }

    private long timedSubLedgerPage(DetailLedgerFixture fixture, int page) {
        long started = System.nanoTime();
        var result = reports.subLedgerBook(
                fixture.actorId(), fixture.ledgerId(), "2026-01", fixture.targetAccountId(), page, 500);
        assertThat(result.data()).hasSize(500);
        return System.nanoTime() - started;
    }

    private DetailLedgerFixture detailLedgerFixture() {
        UUID actor = UUID.randomUUID();
        UUID ledger = createLedger(actor);
        UUID period = period(ledger, "2026-01");
        UUID target = jdbc.queryForObject("""
                select account.id from ledger_account account
                where account.ledger_id = ? and account.normal_balance = 'DEBIT'
                  and not exists (select 1 from ledger_account child
                      where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                order by account.code limit 1
                """, UUID.class, ledger);
        UUID counterpart = jdbc.queryForObject("""
                select account.id from ledger_account account
                where account.ledger_id = ? and account.normal_balance = 'CREDIT'
                  and not exists (select 1 from ledger_account child
                      where child.ledger_id = account.ledger_id and child.parent_id = account.id)
                order by account.code limit 1
                """, UUID.class, ledger);
        List<Object[]> headers = new ArrayList<>(1000);
        List<UUID> voucherIds = new ArrayList<>(1000);
        for (int index = 0; index < 1000; index++) {
            UUID voucherId = UUID.randomUUID();
            voucherIds.add(voucherId);
            headers.add(new Object[]{voucherId, ledger, period, LocalDate.of(2026, 1, 1).plusDays(index % 28),
                    "detail-1m-" + index, actor, actor, actor});
        }
        jdbc.batchUpdate("""
                insert into voucher (id, ledger_id, period_id, voucher_date, voucher_type, voucher_number,
                    summary, status, current_revision, posted_at, posted_by, version, created_by, updated_by)
                values (?, ?, ?, ?, 'GENERAL', ?, 'stage4 detail-ledger benchmark', 'POSTED', 1, now(), ?, 0, ?, ?)
                """, headers);
        jdbc.execute("alter table voucher_line disable trigger tr_sub_ledger_checkpoint_line_dirty");
        try {
            for (UUID voucherId : voucherIds) {
                jdbc.update("""
                    insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                        original_amount, exchange_rate, base_amount, summary)
                    select md5(cast(? as text) || ':' || series)::uuid, ?, ?, series, ?, 'DEBIT', 'CNY',
                        1.00, 1.00, 1.00, 'stage4 detail-ledger benchmark'
                    from generate_series(1, 1000) series
                    """, voucherId, ledger, voucherId, target);
                jdbc.update("""
                    insert into voucher_line (id, ledger_id, voucher_id, line_no, account_id, side, currency,
                        original_amount, exchange_rate, base_amount, summary)
                    values (?, ?, ?, 1001, ?, 'CREDIT', 'CNY', 1000.00, 1.00, 1000.00,
                        'stage4 detail-ledger benchmark counterparty')
                    """, UUID.randomUUID(), ledger, voucherId, counterpart);
            }
        } finally {
            jdbc.execute("alter table voucher_line enable trigger tr_sub_ledger_checkpoint_line_dirty");
        }
        jdbc.update("insert into sub_ledger_checkpoint_epoch (ledger_id, epoch) values (?, 1) on conflict (ledger_id) do update set epoch = sub_ledger_checkpoint_epoch.epoch + 1", ledger);
        jdbc.update("update sub_ledger_checkpoint_state set dirty = true where ledger_id = ?", ledger);
        return new DetailLedgerFixture(actor, ledger, target);
    }

    private String explainDetailLedger(DetailLedgerFixture fixture) {
        return explain("""
                select checkpoint.* from sub_ledger_checkpoint checkpoint
                where checkpoint.ledger_id = ? and checkpoint.account_id = ?
                  and checkpoint.period_from = ? and checkpoint.period_to = ?
                  and checkpoint.row_ordinal > ?
                order by checkpoint.row_ordinal limit ?
                """, fixture.ledgerId(), fixture.targetAccountId(), "2026-01", "2026-01", 49_500, 500);
    }

    private long explainBufferBlocks(String explain, String label) {
        String pattern = "temp written=".equals(label) ? "(?:temp )?written=(\\d+)"
                : Pattern.quote(label) + "(\\d+)";
        Matcher matcher = Pattern.compile(pattern).matcher(explain);
        long blocks = 0;
        while (matcher.find()) {
            blocks += Long.parseLong(matcher.group(1));
        }
        return blocks;
    }

    private record DetailLedgerFixture(UUID actorId, UUID ledgerId, UUID targetAccountId) {
    }

    private UUID createLedger(UUID actor) {
        return ledgers.create(new CurrentUserResolver.ResolvedUser(actor, "stage4-" + actor, actor.toString()),
                new LedgerRequests.Create("stage4 performance", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
    }

    private UUID period(UUID ledger, String code) {
        return jdbc.queryForObject("select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledger, code);
    }

    private UUID account(UUID ledger, String code) {
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and code = ?", UUID.class,
                ledger, code);
    }

    private long percentile(List<Long> values, double percentile) {
        int index = (int) Math.ceil(percentile * values.size()) - 1;
        return values.get(Math.max(0, Math.min(index, values.size() - 1)));
    }

    private String explain(String query, Object... args) {
        return String.join("\n", jdbc.query("explain (analyze, buffers, format text) " + query,
                (rs, row) -> rs.getString(1), args));
    }

    private String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
    }

    private long event(UUID ledger, UUID period, UUID aggregate) {
        jdbc.update("""
                insert into balance_projection_event (
                    ledger_id, period_id, aggregate_type, aggregate_id, aggregate_version, event_type, created_at)
                values (?, ?, 'VOUCHER', ?, 1, 'POST', now() - interval '2 days')
                """, ledger, period, aggregate);
        return jdbc.queryForObject("select id from balance_projection_event where aggregate_id = ?",
                Long.class, aggregate);
    }

    private com.example.accounting.reporting.internal.port.BalanceProjectionRepository findProjectionRepository() {
        return applicationContext.getBean(com.example.accounting.reporting.internal.port.BalanceProjectionRepository.class);
    }

    @Autowired
    private org.springframework.context.ApplicationContext applicationContext;
}
