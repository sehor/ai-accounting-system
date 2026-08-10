package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.BalanceRebuildRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class RollingBalanceProjectionIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private BalanceProjectionRepository projection;

    @Autowired
    private ReportingService reports;

    @Autowired
    private BalanceRebuildService rebuilds;

    @Autowired
    private BalanceRebuildRepository rebuildRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void asynchronouslyRollsLeafAndParentBalancesAcrossFuturePeriods() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "projection-test", actorId.toString()),
                new LedgerRequests.Create("rolling projection", "SME", "v1", "CNY",
                        LocalDate.of(2021, 1, 1), false)).id();
        UUID january = period(ledgerId, "2021-01");
        UUID february = period(ledgerId, "2021-02");
        UUID cashParent = account(ledgerId, "1001");
        UUID cashLeaf = ledgers.createAccount(actorId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "银行存款", "ASSET", "DEBIT")).id();
        UUID capital = account(ledgerId, "3001");

        ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(cashLeaf, january, "100", "0"),
                opening(capital, january, "0", "100")));
        ledgers.confirmOpeningBalances(actorId, ledgerId);

        assertThat(state(ledgerId, "2021-02").get("applied"))
                .isNotEqualTo(state(ledgerId, "2021-02").get("enqueued"));
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "0.00");
        assertBalance(ledgerId, "2021-02", cashParent, "100.00", "0.00", "100.00", "0.00");

        VoucherResponses.Voucher voucher = vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                february, LocalDate.of(2021, 2, 10), "GENERAL", "1", "cash withdrawal",
                List.of(line(capital, "DEBIT", "30"), line(cashLeaf, "CREDIT", "30"))));

        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "100.00", "0.00");
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "70.00", "0.00");
        assertBalance(ledgerId, "2021-03", cashLeaf, "70.00", "0.00", "70.00", "0.00");
        assertBalance(ledgerId, "2021-03", cashParent, "70.00", "0.00", "70.00", "0.00");

        vouchers.update(actorId, ledgerId, voucher.id(), new VoucherRequests.Update(
                voucher.version(), february, voucher.voucherDate(), voucher.voucherType(), voucher.voucherNumber(),
                "cash overdraft", List.of(line(capital, "DEBIT", "120"), line(cashLeaf, "CREDIT", "120"))));
        drainProjection();
        assertBalance(ledgerId, "2021-02", cashLeaf, "100.00", "0.00", "0.00", "20.00");
        assertBalance(ledgerId, "2021-03", cashLeaf, "0.00", "20.00", "0.00", "20.00");
        assertBalance(ledgerId, "2021-03", cashParent, "0.00", "20.00", "0.00", "20.00");

        PeriodRange range = new PeriodRange("2021-02", "2021-03");
        ReportResponses.TrialBalanceLine cash = reports.trialBalance(actorId, ledgerId, range, false).stream()
                .filter(line -> line.accountId().equals(cashLeaf)).findFirst().orElseThrow();
        assertThat(cash.openingDebit()).isEqualByComparingTo("100.00");
        assertThat(cash.periodCredit()).isEqualByComparingTo("120.00");
        assertThat(cash.closingCredit()).isEqualByComparingTo("20.00");
        assertThat(cash.balance()).isEqualByComparingTo("-20.00");

        ReportResponses.SubLedgerPage detail = reports.subLedgerBook(
                actorId, ledgerId, range, cashLeaf, 1, 50);
        assertThat(detail.periodFrom()).isEqualTo("2021-02");
        assertThat(detail.periodTo()).isEqualTo("2021-03");
        assertThat(detail.periodCode()).isNull();
        assertThat(detail.openingBalance()).isEqualByComparingTo("100.00");
        assertThat(detail.data()).hasSize(1);
        assertThat(detail.endingDirection()).isEqualTo("CREDIT");
        assertThat(detail.endingBalance()).isEqualByComparingTo("20.00");

        BalanceRebuildResponses.Job requested = rebuilds.request(actorId, ledgerId,
                new BalanceRebuildRequests.Create("2021-02", "2021-02", "verify downstream rebuild"));
        assertThat(rebuildRepository.processNextJob()).isTrue();
        BalanceRebuildResponses.Job rebuilt = rebuilds.find(actorId, ledgerId, requested.id());
        assertThat(rebuilt.status()).isEqualTo("SUCCEEDED");
        assertThat(rebuilt.periodFrom()).isEqualTo("2021-02");
        assertThat(rebuilt.periodTo()).isEqualTo(jdbc.queryForObject(
                "select max(period_code) from accounting_period where ledger_id = ?", String.class, ledgerId));
        assertThat(rebuilt.processedPeriods()).isEqualTo(rebuilt.totalPeriods());
    }

    @Test
    void rollsBackPostedVoucherWhenOutboxWriteFails() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "projection-rollback", actorId.toString()),
                new LedgerRequests.Create("projection rollback", "SME", "v1", "CNY",
                        LocalDate.of(2022, 1, 1), false)).id();
        UUID periodId = period(ledgerId, "2022-01");
        UUID cash = account(ledgerId, "1001");
        UUID capital = account(ledgerId, "3001");
        jdbc.execute("""
                create function reject_balance_event() returns trigger language plpgsql as $$
                begin raise exception 'simulated outbox failure'; end $$
                """);
        jdbc.execute("""
                create trigger reject_balance_event before insert on balance_projection_event
                for each row execute function reject_balance_event()
                """);
        try {
            assertThatThrownBy(() -> vouchers.create(actorId, ledgerId, new VoucherRequests.Create(
                    periodId, LocalDate.of(2022, 1, 10), "GENERAL", "rollback-1", "must rollback",
                    List.of(line(cash, "DEBIT", "50"), line(capital, "CREDIT", "50")))))
                    .hasMessageContaining("simulated outbox failure");
            assertThat(jdbc.queryForObject("""
                    select count(*) from voucher where ledger_id = ? and voucher_number = 'rollback-1'
                    """, Long.class, ledgerId)).isZero();
        } finally {
            jdbc.execute("drop trigger reject_balance_event on balance_projection_event");
            jdbc.execute("drop function reject_balance_event()");
        }
    }

    @Test
    void rejectsOpeningBalancesOutsideTheFirstAccountingPeriod() {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(new CurrentUserResolver.ResolvedUser(
                        actorId, "opening-period", actorId.toString()),
                new LedgerRequests.Create("opening period", "SME", "v1", "CNY",
                        LocalDate.of(2023, 1, 1), false)).id();
        UUID february = period(ledgerId, "2023-02");
        assertThatThrownBy(() -> ledgers.replaceOpeningBalances(actorId, ledgerId, List.of(
                opening(account(ledgerId, "1001"), february, "10", "0"),
                opening(account(ledgerId, "3001"), february, "0", "10"))))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(error -> assertThat(((ApiProblemException) error).code())
                        .isEqualTo("OPENING_BALANCE_PERIOD_INVALID"));
    }

    private LedgerRequests.OpeningBalanceLine opening(
            UUID accountId, UUID periodId, String debit, String credit) {
        return new LedgerRequests.OpeningBalanceLine(accountId, periodId, "CNY", "",
                new BigDecimal(debit), new BigDecimal(credit), BigDecimal.ONE);
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "projection");
    }

    private UUID period(UUID ledgerId, String code) {
        return jdbc.queryForObject("""
                select id from accounting_period where ledger_id = ? and period_code = ?
                """, UUID.class, ledgerId, code);
    }

    private UUID account(UUID ledgerId, String code) {
        return jdbc.queryForObject("""
                select id from ledger_account where ledger_id = ? and code = ?
                """, UUID.class, ledgerId, code);
    }

    private Map<String, Object> state(UUID ledgerId, String periodCode) {
        return jdbc.queryForMap("""
                select coalesce(last_enqueued_event_id, 0) enqueued,
                    coalesce(last_applied_event_id, 0) applied
                from balance_projection_state s
                join accounting_period p on p.ledger_id = s.ledger_id and p.id = s.period_id
                where s.ledger_id = ? and p.period_code = ?
                """, ledgerId, periodCode);
    }

    private void drainProjection() {
        while (projection.applyPendingBatch(200, 5000)) {
            // A pass catches up one ledger; shared integration contexts may contain several ledgers.
        }
    }

    private void assertBalance(UUID ledgerId, String periodCode, UUID accountId,
                               String openingDebit, String openingCredit,
                               String closingDebit, String closingCredit) {
        Map<String, Object> row = jdbc.queryForMap("""
                select opening_debit_base, opening_credit_base,
                    closing_debit_base, closing_credit_base
                from account_period_balance b
                join accounting_period p on p.ledger_id = b.ledger_id and p.id = b.period_id
                where b.ledger_id = ? and p.period_code = ? and b.account_id = ?
                """, ledgerId, periodCode, accountId);
        assertThat((BigDecimal) row.get("opening_debit_base")).isEqualByComparingTo(openingDebit);
        assertThat((BigDecimal) row.get("opening_credit_base")).isEqualByComparingTo(openingCredit);
        assertThat((BigDecimal) row.get("closing_debit_base")).isEqualByComparingTo(closingDebit);
        assertThat((BigDecimal) row.get("closing_credit_base")).isEqualByComparingTo(closingCredit);
    }
}
