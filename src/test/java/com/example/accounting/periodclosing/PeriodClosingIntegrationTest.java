package com.example.accounting.periodclosing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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

    @Test
    void generatesExpenseAndRevenueTransfersIdempotently() {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create("period-closing", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        UUID cash = ledgers.accountId(ledger, "1001");
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
        UUID cash = ledgers.accountId(ledger, "1001");
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
        UUID cash = ledgers.accountId(ledger, "1001");
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
        UUID cash = ledgers.accountId(ledger, "1001");
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
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and category = ? and status = 'ACTIVE' "
                + "and not exists (select 1 from ledger_account child where child.ledger_id = ledger_account.ledger_id and child.parent_id = ledger_account.id) "
                + "order by code limit 1", UUID.class, ledger, category);
    }

    private VoucherRequests.Line line(UUID account, String side, String amount) {
        return new VoucherRequests.Line(account, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "line");
    }

    private PeriodClosingResponses.Status statusAfterProjectionIsReady(UUID user, UUID ledger, UUID period)
            throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            PeriodClosingResponses.Status status = closing.status(user, ledger, period);
            boolean projectionReady = status.blockers().stream()
                    .noneMatch(blocker -> "BALANCE_PROJECTION_NOT_READY".equals(blocker.code()));
            if (projectionReady) return status;
            Thread.sleep(250);
        }
        return closing.status(user, ledger, period);
    }
}
