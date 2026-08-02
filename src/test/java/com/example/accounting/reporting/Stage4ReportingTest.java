package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.shared.web.ApiProblemException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Stage4ReportingTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void reportsOnlyPostedVoucherAmounts() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("reporting", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID capitalId = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Posted",
                List.of(line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "50"),
                        line(revenueId, "CREDIT", "50"))));
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01")).isEmpty();
        voucherService.validate(userId, ledgerId, voucher.id());
        voucherService.post(userId, ledgerId, voucher.id());

        List<ReportResponses.TrialBalanceLine> lines = reportingService.trialBalance(userId, ledgerId, "2026-01");
        assertThat(lines).hasSize(3);
        assertThat(lines).extracting(ReportResponses.TrialBalanceLine::debit)
                .contains(new BigDecimal("100.00"));
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").totalLines()).isGreaterThan(0);
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").lines())
                .filteredOn(line -> line.code().equals("3001"))
                .singleElement()
                .extracting(ReportResponses.StatementLine::amount)
                .isEqualTo(new BigDecimal("50.00"));
        assertThat(reportingService.incomeStatement(userId, ledgerId, "2026-01").totalLines()).isGreaterThan(0);
        assertThat(reportingService.generalLedger(userId, ledgerId, "2026-01")).hasSize(3);
        assertThat(reportingService.subLedger(userId, ledgerId, "2026-01")).hasSize(3);
        List<ReportResponses.FinanceQueryLine> query = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("NET", "2026-01", "2026-01", List.of("ACCOUNT"),
                        new FinanceQueryRequests.Filters(List.of("1001"), "CNY")));
        assertThat(query).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("1001");
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        });
        assertThatThrownBy(() -> reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("SQL", null, null, List.of("ACCOUNT"), null)))
                .isInstanceOf(ApiProblemException.class);

        List<ReportResponses.FinanceQueryLine> aggregate = reportingService.financeQuery(userId, ledgerId,
                new FinanceQueryRequests.Query("DEBIT", "2026-01", "2026-01", List.of("MONTH"),
                        new FinanceQueryRequests.Filters(null, "CNY")));
        assertThat(aggregate).singleElement().satisfies(line -> {
            assertThat(line.groupKey()).isEqualTo("2026-01");
            assertThat(line.amount()).isEqualByComparingTo("100.00");
        });

        jdbcTemplate.update("""
                update report_formula_snapshot
                set formula_json = jsonb_set(formula_json, '{creditCategories}', '[]'::jsonb)
                where ledger_id = ? and code = 'BALANCE_SHEET'
                """, ledgerId);
        assertThat(reportingService.balanceSheet(userId, ledgerId, "2026-01").lines())
                .extracting(ReportResponses.StatementLine::code)
                .containsExactly("1001");

        VoucherResponses.Voucher reversal = voucherService.reverse(userId, ledgerId, voucher.id());
        voucherService.validate(userId, ledgerId, reversal.id());
        voucherService.post(userId, ledgerId, reversal.id());
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .allSatisfy(line -> assertThat(line.balance()).isEqualByComparingTo(BigDecimal.ZERO));
    }

    @Test
    void optionallyRollsLeafAmountsIntoParentsWithoutChangingDirectTotals() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("parent reporting", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cash = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("1001.01", "库存现金-人民币", "ASSET", "DEBIT")).id();
        UUID capital = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "2", "Parent rollup",
                List.of(line(cash, "DEBIT", "100"), line(capital, "CREDIT", "100"))));
        voucherService.validate(userId, ledgerId, voucher.id());
        voucherService.post(userId, ledgerId, voucher.id());

        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .extracting(ReportResponses.TrialBalanceLine::code)
                .containsExactlyInAnyOrder("1001.01", "3001");
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01", true))
                .filteredOn(line -> line.code().equals("1001"))
                .singleElement()
                .extracting(ReportResponses.TrialBalanceLine::debit)
                .isEqualTo(new BigDecimal("100.00"));
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "report");
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbcTemplate.queryForObject(sql, UUID.class, ledgerId);
    }
}
