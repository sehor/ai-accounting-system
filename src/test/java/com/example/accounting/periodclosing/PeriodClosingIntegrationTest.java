package com.example.accounting.periodclosing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class PeriodClosingIntegrationTest {
    @Autowired private LedgerService ledgers;
    @Autowired private VoucherService vouchers;
    @Autowired private PeriodClosingService closing;
    @Autowired private FixedAssetService fixedAssets;
    @Autowired private JdbcTemplate jdbc;

    @Autowired private com.example.accounting.reporting.internal.port.BalanceProjectionRepository projection;

    /** Detailed cash flow item attached to test lines so external cash vouchers stay classified. */
    private UUID defaultCashItem;

    @Test
    void generatesExpenseAndRevenueTransfersIdempotently() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("period-closing", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID expense = account(ledger, "PERIOD_EXPENSE");
        UUID revenue = account(ledger, "OPERATING_REVENUE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10), "GENERAL", "1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 11), "GENERAL", "2", "revenue",
                List.of(line(cash, "DEBIT", "200"), line(revenue, "CREDIT", "200"))));

        PeriodClosingResponses.Step expenseStep = closing.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER);
        PeriodClosingResponses.Step retry = closing.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER);
        assertThat(expenseStep.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(retry.voucherId()).isEqualTo(expenseStep.voucherId());
        assertThat(closing.generate(user, ledger, period, PeriodClosingStepType.REVENUE_TRANSFER).status())
                .isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(jdbc.queryForObject("select count(*) from voucher where ledger_id = ? and source_type = 'PERIOD_CLOSING'",
                Long.class, ledger)).isEqualTo(2L);
    }

    @Test
    void statusDoesNotCreateOrUpdateStepsAndMissingStepsAreTransient() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("pure-closing-status", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        long before = stepWriteFingerprint(ledger, period);

        PeriodClosingResponses.Status first = closing.status(user, ledger, period);
        PeriodClosingResponses.Status second = closing.status(user, ledger, period);

        assertThat(first.steps()).isNotEmpty();
        assertThat(first.steps()).allSatisfy(step -> assertThat(step.status())
                .isIn(PeriodClosingStepStatus.PENDING, PeriodClosingStepStatus.NOT_REQUIRED,
                        PeriodClosingStepStatus.BLOCKED));
        assertThat(second.steps()).isEqualTo(first.steps());
        assertThat(stepWriteFingerprint(ledger, period)).isEqualTo(before);
        assertThat(jdbc.queryForObject("select count(*) from period_closing_step where ledger_id = ? and period_id = ?",
                Long.class, ledger, period)).isZero();
    }

    @Test
    void viewerAndAgentRepeatedAndConcurrentStatusReadsDoNotMutateClosingOwnership() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(owner, "owner", owner.toString()),
                new LedgerRequests.Create("closing-status-roles", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        createDepreciableAsset(owner, ledger, "FA-STATUS-1");
        PeriodClosingResponses.Step generated = closing.generate(
                owner, ledger, period, PeriodClosingStepType.DEPRECIATION);
        assertThat(generated.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);

        UUID viewer = UUID.randomUUID();
        UUID agent = UUID.randomUUID();
        ledgers.create(new CurrentUserResolver.ResolvedUser(viewer, "viewer", viewer.toString()),
                new LedgerRequests.Create("viewer-identity", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false));
        ledgers.create(new CurrentUserResolver.ResolvedUser(agent, "agent", agent.toString()),
                new LedgerRequests.Create("agent-identity", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false));
        ledgers.addMember(owner, ledger, new LedgerRequests.AddMember(viewer, LedgerRole.VIEWER));
        ledgers.addMember(owner, ledger, new LedgerRequests.AddMember(agent, LedgerRole.AGENT));

        Map<String, TableFingerprint> before = closingStorageSnapshot(ledger);
        assertThat(closing.status(viewer, ledger, period).steps()).isNotEmpty();
        assertThat(closing.status(viewer, ledger, period).steps()).isNotEmpty();
        assertThat(closing.status(agent, ledger, period).steps()).isNotEmpty();
        assertThat(closing.status(agent, ledger, period).steps()).isNotEmpty();

        CyclicBarrier barrier = new CyclicBarrier(4);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Future<PeriodClosingResponses.Status>> reads = List.of(
                    executor.submit(() -> concurrentStatus(barrier, viewer, ledger, period)),
                    executor.submit(() -> concurrentStatus(barrier, viewer, ledger, period)),
                    executor.submit(() -> concurrentStatus(barrier, agent, ledger, period)),
                    executor.submit(() -> concurrentStatus(barrier, agent, ledger, period)));
            for (Future<PeriodClosingResponses.Status> read : reads) {
                PeriodClosingResponses.Status status = read.get();
                assertThat(status.steps())
                        .filteredOn(step -> step.step() == PeriodClosingStepType.DEPRECIATION)
                        .singleElement().satisfies(step -> {
                    assertThat(step.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
                    assertThat(step.voucherId()).isEqualTo(generated.voucherId());
                });
            }
        } finally {
            executor.shutdownNow();
        }

        assertThat(closingStorageSnapshot(ledger)).isEqualTo(before);
    }

    @Test
    void concurrentFirstGenerationUpsertsOneStepAndOneSourceVoucher() throws Exception {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("concurrent-closing-generate", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID expense = account(ledger, "PERIOD_EXPENSE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10),
                "GENERAL", "CONCURRENT-1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<PeriodClosingResponses.Step> first = executor.submit(() -> closing.generate(
                    user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER));
            Future<PeriodClosingResponses.Step> second = executor.submit(() -> closing.generate(
                    user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER));
            assertThat(second.get().voucherId()).isEqualTo(first.get().voucherId());
        } finally {
            executor.shutdownNow();
        }
        assertThat(jdbc.queryForObject("""
                select count(*) from period_closing_step
                where ledger_id = ? and period_id = ? and step_type = 'EXPENSE_TRANSFER'
                """, Long.class, ledger, period)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from voucher
                where ledger_id = ? and source_type = 'PERIOD_CLOSING' and deleted_at is null
                """, Long.class, ledger)).isEqualTo(1L);
    }

    @Test
    void generatePersistsNotRequiredButStatusRemainsReadOnly() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("persist-not-required", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");

        assertThat(closing.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER).status())
                .isEqualTo(PeriodClosingStepStatus.NOT_REQUIRED);
        assertThat(jdbc.queryForObject("""
                select status from period_closing_step
                where ledger_id = ? and period_id = ? and step_type = 'EXPENSE_TRANSFER'
                """, String.class, ledger, period)).isEqualTo("NOT_REQUIRED");
        long fingerprint = stepWriteFingerprint(ledger, period);
        closing.status(user, ledger, period);
        assertThat(stepWriteFingerprint(ledger, period)).isEqualTo(fingerprint);
    }

    @Test
    void resetTransferClearsOwnershipThenDeletesVoucherAndPersistsPending() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("reset-transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID expense = account(ledger, "PERIOD_EXPENSE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10),
                "GENERAL", "RESET-1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));
        UUID generated = closing.generate(user, ledger, period,
                PeriodClosingStepType.EXPENSE_TRANSFER).voucherId();

        PeriodClosingResponses.Step reset = closing.resetStep(user, ledger, period,
                PeriodClosingStepType.EXPENSE_TRANSFER, "correct source facts");

        assertThat(reset.status()).isEqualTo(PeriodClosingStepStatus.PENDING);
        assertThat(reset.voucherId()).isNull();
        assertThat(jdbc.queryForObject("select count(*) from voucher where id = ? and deleted_at is null",
                Long.class, generated)).isZero();
    }

    @Test
    void resetDepreciationClearsClosingReferenceBeforeOwnedRunCancellation() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("reset-depreciation", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID category = fixedAssets.createCategory(user, ledger, new FixedAssetRequests.CategoryCreate(
                "FA-RESET", "reset depreciation", 36, BigDecimal.ZERO,
                cash, cash, cash, cash, cash, cash, cash)).id();
        fixedAssets.createAsset(user, ledger, new FixedAssetRequests.AssetCreate(
                category, "FA-RESET-1", "reset depreciation asset", BigDecimal.ONE,
                LocalDate.of(2025, 12, 1), new BigDecimal("10000"), BigDecimal.ZERO,
                36, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null));
        PeriodClosingResponses.Step generated = closing.generate(user, ledger, period,
                PeriodClosingStepType.DEPRECIATION);
        UUID runId = jdbc.queryForObject("""
                select id from fixed_asset_depreciation_run
                where ledger_id = ? and period_id = ? and voucher_id = ?
                """, UUID.class, ledger, period, generated.voucherId());

        PeriodClosingResponses.Step reset = closing.resetStep(user, ledger, period,
                PeriodClosingStepType.DEPRECIATION, "recalculate depreciation");

        assertThat(reset.status()).isEqualTo(PeriodClosingStepStatus.PENDING);
        assertThat(reset.voucherId()).isNull();
        assertThat(jdbc.queryForObject("select status from fixed_asset_depreciation_run where id = ?",
                String.class, runId)).isEqualTo("CANCELLED");
        assertThat(jdbc.queryForObject("select count(*) from voucher where id = ? and deleted_at is null",
                Long.class, generated.voucherId())).isZero();
    }

    @Test
    void repeatedDepreciationGenerationKeepsTheExistingOwnership() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("repeat-depreciation-generate", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        createDepreciableAsset(user, ledger, "FA-REPEAT-1");

        PeriodClosingResponses.Step first = closing.generate(
                user, ledger, period, PeriodClosingStepType.DEPRECIATION);
        PeriodClosingResponses.Step retry = closing.generate(
                user, ledger, period, PeriodClosingStepType.DEPRECIATION);

        assertThat(first.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(retry.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(retry.voucherId()).isEqualTo(first.voucherId());
        assertSingleDepreciationOwnership(ledger, period, first.voucherId());
    }

    @Test
    void concurrentDepreciationGenerationKeepsOneRunVoucherAndStepOwnership() throws Exception {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("concurrent-depreciation-generate", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        createDepreciableAsset(user, ledger, "FA-CONCURRENT-1");

        ExecutorService executor = Executors.newFixedThreadPool(2);
        PeriodClosingResponses.Step first;
        PeriodClosingResponses.Step second;
        try {
            Future<PeriodClosingResponses.Step> firstFuture = executor.submit(() -> closing.generate(
                    user, ledger, period, PeriodClosingStepType.DEPRECIATION));
            Future<PeriodClosingResponses.Step> secondFuture = executor.submit(() -> closing.generate(
                    user, ledger, period, PeriodClosingStepType.DEPRECIATION));
            first = firstFuture.get();
            second = secondFuture.get();
        } finally {
            executor.shutdownNow();
        }

        assertThat(first.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(second.status()).isEqualTo(PeriodClosingStepStatus.GENERATED);
        assertThat(second.voucherId()).isEqualTo(first.voucherId());
        assertSingleDepreciationOwnership(ledger, period, first.voucherId());
    }

    @Test
    void rejectsYearEndStepOutsideDecember() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("period-closing-year", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        assertThatThrownBy(() -> closing.generate(user, ledger, period, PeriodClosingStepType.YEAR_END_PROFIT_TRANSFER))
                .isInstanceOf(ApiProblemException.class)
                .extracting(e -> ((ApiProblemException) e).code()).isEqualTo("YEAR_END_STEP_NOT_ALLOWED");
    }

    @Test
    void closesWhenTrialBalanceIsBalancedEvenIfTransferStepsArePending() throws InterruptedException {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("close-without-transfers", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID expense = account(ledger, "PERIOD_EXPENSE");
        UUID revenue = account(ledger, "OPERATING_REVENUE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10), "GENERAL", "1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 11), "GENERAL", "2", "revenue",
                List.of(line(cash, "DEBIT", "200"), line(revenue, "CREDIT", "200"))));

        PeriodClosingResponses.Status status = statusAfterProjectionIsReady(user, ledger, period);
        assertThat(status.trialBalance().balanced()).isTrue();
        assertThat(status.steps()).anySatisfy(step -> assertThat(step.step())
                .isEqualTo(PeriodClosingStepType.EXPENSE_TRANSFER));
        assertThat(status.blockers()).extracting(PeriodClosingResponses.Blocker::code)
                .doesNotContain("PERIOD_CLOSING_INCOMPLETE");
        assertThat(status.canClose()).isTrue();
        assertThat(ledgers.closePeriod(user, ledger, period,
                new LedgerRequests.PeriodAction("month end")).status()).isEqualTo("CLOSED");
    }

    @Test
    void detectsExistingPostedTransferVoucherWithoutRequiringPeriodClosingStepRecord() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("detect-existing-transfer", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID expense = account(ledger, "PERIOD_EXPENSE");
        UUID revenue = account(ledger, "OPERATING_REVENUE");
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 10), "GENERAL", "1", "expense",
                List.of(line(expense, "DEBIT", "100"), line(cash, "CREDIT", "100"))));
        vouchers.create(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 11), "GENERAL", "2", "revenue",
                List.of(line(cash, "DEBIT", "200"), line(revenue, "CREDIT", "200"))));

        vouchers.createGenerated(user, ledger, new VoucherRequests.Create(period, LocalDate.of(2026, 1, 31),
                        "GENERAL", "3", "carry forward", List.of(
                        line(expense, "CREDIT", "100"), line(cash, "DEBIT", "100"),
                        line(revenue, "DEBIT", "200"), line(cash, "CREDIT", "200"))),
                "detect-existing-transfer-voucher", "PERIOD_CLOSING", UUID.randomUUID());

        PeriodClosingResponses.Status status = closing.status(user, ledger, period);
        assertThat(status.steps()).allSatisfy(step -> assertThat(step.status())
                .isEqualTo(PeriodClosingStepStatus.NOT_REQUIRED));
        assertThat(status.blockers()).extracting(PeriodClosingResponses.Blocker::code)
                .doesNotContain("PERIOD_CLOSING_INCOMPLETE");
    }

    @Test
    void closesEvenWhenFixedAssetDepreciationIsPending() throws InterruptedException {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("close-with-pending-depreciation", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = accountId(ledger, "1001");
        UUID category = fixedAssets.createCategory(user, ledger, new FixedAssetRequests.CategoryCreate(
                "FA-CLOSE", "close pending depreciation", 36, BigDecimal.ZERO,
                cash, cash, cash, cash, cash, cash, cash)).id();
        fixedAssets.createAsset(user, ledger, new FixedAssetRequests.AssetCreate(
                category, "FA-0001", "close pending depreciation asset", BigDecimal.ONE,
                LocalDate.of(2025, 12, 1), new BigDecimal("10000"), BigDecimal.ZERO,
                36, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null));

        assertThat(fixedAssets.periodBlockers(user, ledger, period)).isNotEmpty();
        PeriodClosingResponses.Status status = statusAfterProjectionIsReady(user, ledger, period);
        assertThat(status.blockers()).extracting(PeriodClosingResponses.Blocker::code)
                .doesNotContain("FIXED_ASSET_DEPRECIATION_INCOMPLETE");
        assertThat(status.canClose()).isTrue();
        assertThat(ledgers.closePeriod(user, ledger, period,
                new LedgerRequests.PeriodAction("month end")).status()).isEqualTo("CLOSED");
    }

    private UUID account(UUID ledger, String category) {
        defaultCashItem = cashItem(ledger);
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and category = ? and status = 'ACTIVE' "
                + "and not exists (select 1 from ledger_account child where child.ledger_id = ledger_account.ledger_id and child.parent_id = ledger_account.id) "
                + "order by code limit 1", UUID.class, ledger, category);
    }

    private UUID accountId(UUID ledger, String code) {
        defaultCashItem = cashItem(ledger);
        return ledgers.accountId(ledger, code);
    }

    private UUID cashItem(UUID ledger) {
        List<UUID> items = jdbc.queryForList(
                "select id from cash_flow_item where ledger_id = ? and code = 'SME_CF_01_SALES_RECEIPTS'",
                UUID.class, ledger);
        return items.isEmpty() ? null : items.getFirst();
    }

    private VoucherRequests.Line line(UUID account, String side, String amount) {
        return new VoucherRequests.Line(account, side, "CNY", new BigDecimal(amount), BigDecimal.ONE,
                "line", defaultCashItem, null, null, null);
    }

    private void createDepreciableAsset(UUID user, UUID ledger, String assetCode) {
        UUID cash = accountId(ledger, "1001");
        UUID category = fixedAssets.createCategory(user, ledger, new FixedAssetRequests.CategoryCreate(
                "FA-GENERATE", "depreciation generation", 36, BigDecimal.ZERO,
                cash, cash, cash, cash, cash, cash, cash)).id();
        fixedAssets.createAsset(user, ledger, new FixedAssetRequests.AssetCreate(
                category, assetCode, "depreciation generation asset", BigDecimal.ONE,
                LocalDate.of(2025, 12, 1), new BigDecimal("10000"), BigDecimal.ZERO,
                36, BigDecimal.ZERO, BigDecimal.ZERO, 0, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null));
    }

    private void assertSingleDepreciationOwnership(UUID ledger, UUID period, UUID voucherId) {
        assertThat(jdbc.queryForObject("""
                select count(*) from period_closing_step step
                join fixed_asset_depreciation_run run
                  on run.ledger_id = step.ledger_id and run.period_id = step.period_id
                 and run.voucher_id = step.voucher_id
                join voucher on voucher.ledger_id = step.ledger_id and voucher.id = step.voucher_id
                where step.ledger_id = ? and step.period_id = ? and step.step_type = 'DEPRECIATION'
                  and step.status = 'GENERATED' and step.voucher_id = ?
                  and run.status = 'POSTED' and voucher.deleted_at is null
                """, Long.class, ledger, period, voucherId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from fixed_asset_depreciation_run
                where ledger_id = ? and period_id = ? and status = 'POSTED'
                """, Long.class, ledger, period)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("""
                select count(*) from voucher
                where ledger_id = ? and id = ? and source_type = 'FIXED_ASSET_DEPRECIATION'
                  and deleted_at is null
                """, Long.class, ledger, voucherId)).isEqualTo(1L);
    }

    private PeriodClosingResponses.Status concurrentStatus(
            CyclicBarrier barrier, UUID actor, UUID ledger, UUID period) {
        try {
            barrier.await();
            return closing.status(actor, ledger, period);
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private Map<String, TableFingerprint> closingStorageSnapshot(UUID ledger) {
        Map<String, TableFingerprint> snapshot = new LinkedHashMap<>();
        for (String table : List.of(
                "period_closing_step",
                "period_closing_setting",
                "voucher",
                "voucher_line",
                "fixed_asset_depreciation_run",
                "fixed_asset_depreciation_line",
                "audit_revision")) {
            snapshot.put(table, tableFingerprint(table, ledger));
        }
        return Map.copyOf(snapshot);
    }

    private TableFingerprint tableFingerprint(String table, UUID ledger) {
        String sql = """
                select count(*) row_count,
                    md5(coalesce(string_agg(row_to_json(snapshot_row)::text, '|'
                        order by row_to_json(snapshot_row)::text), '')) content_hash
                from (select * from %s where ledger_id = ?) snapshot_row
                """.formatted(table);
        return jdbc.queryForObject(sql, (rs, row) ->
                new TableFingerprint(rs.getLong("row_count"), rs.getString("content_hash")), ledger);
    }

    private long stepWriteFingerprint(UUID ledger, UUID period) {
        return jdbc.queryForObject("""
                select coalesce(sum(hashtext(id::text || ':' || status || ':' || updated_at::text)), 0)
                from period_closing_step where ledger_id = ? and period_id = ?
                """, Long.class, ledger, period);
    }

    private record TableFingerprint(long rowCount, String contentHash) { }

    private PeriodClosingResponses.Status statusAfterProjectionIsReady(UUID user, UUID ledger, UUID period)
            throws InterruptedException {
        for (int attempt = 0; attempt < 60; attempt++) {
            projection.applyPendingBatch(200, 5000);
            PeriodClosingResponses.Status status = closing.status(user, ledger, period);
            boolean projectionReady = status.blockers().stream()
                    .noneMatch(blocker -> "BALANCE_PROJECTION_NOT_READY".equals(blocker.code()));
            if (projectionReady) return status;
            Thread.sleep(250);
        }
        return closing.status(user, ledger, period);
    }
}
