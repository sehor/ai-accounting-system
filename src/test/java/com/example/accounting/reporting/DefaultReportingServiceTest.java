package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class DefaultReportingServiceTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private ReportingService reporting;

    @Autowired
    private com.example.accounting.voucher.VoucherService vouchers;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BalanceProjectionRepository projection;

    @Test
    void statutoryStatementCarriesFormulaCodeAndVersion() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "formula metadata");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));
        applyProjection(ledgerId);

        StatutoryReportResponses.Statement result = reporting.statutoryStatement(
                userId, ledgerId, "balance-sheet", "2026-01");

        assertThat(result.formulaCode()).isEqualTo("BALANCE_SHEET");
        assertThat(result.formulaVersion()).isEqualTo(1);
        assertThat(result.groups()).isNotEmpty();
        assertThat(result.checks()).allMatch(StatutoryReportResponses.Check::passed);
    }

    @Test
    void dynamicStatementsCarryFormulaCodeAndVersion() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "dynamic metadata");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));
        applyProjection(ledgerId);

        ReportResponses.Statement result = reporting.balanceSheet(userId, ledgerId, "2026-01");

        assertThat(result.formulaCode()).isEqualTo("BALANCE_SHEET");
        assertThat(result.formulaVersion()).isEqualTo(1);
        assertThat(result.lines()).extracting(ReportResponses.StatementLine::code)
                .contains("1001", "3001");
    }

    @Test
    void rejectsCasAndForeignCurrencyStatutoryRequests() {
        UUID userId = UUID.randomUUID();
        UUID casId = createLedger(userId, "CAS", "2006-18", "cas statutory");
        assertThatThrownBy(() -> reporting.statutoryStatement(userId, casId, "balance-sheet", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_REPORT_UNSUPPORTED_STANDARD"));
    }

    @Test
    void doesNotFallBackToLiveFactsWhenStatutoryProjectionIsPending() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "pending statutory");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));

        assertThatThrownBy(() -> reporting.statutoryStatement(
                userId, ledgerId, "income-statement", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_REPORT_PROJECTION_PENDING"));
    }

    @Test
    void doesNotFallBackToLiveFactsWhenIncomeProjectionIsPending() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "pending income");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));

        assertThatThrownBy(() -> reporting.incomeStatement(userId, ledgerId, "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("INCOME_STATEMENT_PROJECTION_PENDING"));
    }

    @Test
    void rejectsUnknownPeriodsAndCrossLedgerAccountsAtTheBoundary() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "boundary");

        assertThatThrownBy(() -> reporting.generalLedgerBook(userId, ledgerId, "bad", 1, 50))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("PERIOD_RANGE_INVALID"));
        assertThatThrownBy(() -> reporting.subLedgerBook(
                userId, ledgerId, "2026-01", UUID.randomUUID(), 1, 50))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("ACCOUNT_NOT_FOUND"));
    }

    private void postVoucher(UUID userId, UUID ledgerId, String periodCode, String number,
                             List<VoucherRequests.Line> lines) {
        UUID periodId = jdbc.queryForObject(
                "select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, periodCode);
        vouchers.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "GENERAL", number, "service test", lines));
    }

    private VoucherRequests.Line line(UUID ledgerId, String code, String side, String amount) {
        UUID accountId = jdbc.queryForObject(
                "select id from ledger_account where ledger_id = ? and code = ?",
                UUID.class, ledgerId, code);
        return new VoucherRequests.Line(accountId, side, "CNY",
                new BigDecimal(amount), BigDecimal.ONE, "test");
    }

    private UUID createLedger(UUID userId, String standardCode, String standardVersion, String name) {
        CurrentUserResolver.ResolvedUser user =
                new CurrentUserResolver.ResolvedUser(userId, "test", UUID.randomUUID().toString());
        return ledgers.create(user, new LedgerRequests.Create(name,
                standardCode, standardVersion, "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }

    private void applyProjection(UUID ledgerId) {
        for (int attempt = 0; attempt < 50 && !projection.status(ledgerId, "2026-01").fresh(); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, "2026-01").fresh()).isTrue();
    }
}
