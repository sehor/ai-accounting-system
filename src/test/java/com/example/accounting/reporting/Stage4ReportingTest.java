package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
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

    @Autowired
    private BalanceProjectionRepository projection;

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
        assertThat(voucher.status()).isEqualTo("POSTED");

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
        applyProjection(ledgerId);
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

        VoucherResponses.Voucher updated = voucherService.update(userId, ledgerId, voucher.id(),
                new VoucherRequests.Update(voucher.version(), periodId, voucher.voucherDate(), voucher.voucherType(),
                        voucher.voucherNumber(), "Updated", List.of(
                        line(cashId, "DEBIT", "100"), line(capitalId, "CREDIT", "100"))));
        assertThat(updated.status()).isEqualTo("POSTED");
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .extracting(ReportResponses.TrialBalanceLine::balance)
                .containsExactlyInAnyOrder(new BigDecimal("100.00"), new BigDecimal("-100.00"));
    }

    @Test
    void optionallyRollsLeafAmountsIntoParentsWithoutChangingDirectTotals() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("parent reporting", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cash = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "库存现金-人民币", "CURRENT_ASSET", "DEBIT")).id();
        UUID capital = id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId);
        VoucherResponses.Voucher voucher = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "2", "Parent rollup",
                List.of(line(cash, "DEBIT", "100"), line(capital, "CREDIT", "100"))));
        assertThat(voucher.status()).isEqualTo("POSTED");

        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01"))
                .extracting(ReportResponses.TrialBalanceLine::code)
                .containsExactlyInAnyOrder("100101", "3001");
        assertThat(reportingService.trialBalance(userId, ledgerId, "2026-01", true))
                .filteredOn(line -> line.code().equals("1001"))
                .singleElement()
                .extracting(ReportResponses.TrialBalanceLine::debit)
                .isEqualTo(new BigDecimal("100.00"));
    }

    @Test
    void incomeStatementReadsOperatingProjectionAfterManualProfitLossTransfer() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("income statement closing transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher transfer = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Manual closing",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "100"))));
        assertThat(accountingRole(transfer.id())).isEqualTo("PROFIT_LOSS_TRANSFER");
        applyProjection(ledgerId);

        assertThat(reportingService.incomeStatement(userId, ledgerId, "2026-01").lines())
                .singleElement()
                .satisfies(line -> {
                    assertThat(line.code()).isEqualTo("5001");
                    assertThat(line.amount()).isEqualByComparingTo("100.00");
                });
    }

    @Test
    void statutoryIncomeStatementUsesOperatingProjectionAfterProfitLossTransfer() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("statutory income closing transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Manual closing",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "100"))));
        applyProjection(ledgerId);

        StatutoryReportResponses.Statement result = reportingService.statutoryStatement(
                userId, ledgerId, "income-statement", "2026-01");
        StatutoryReportResponses.Line revenue = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 1).findFirst().orElseThrow();
        StatutoryReportResponses.Line netProfit = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 32).findFirst().orElseThrow();
        assertThat(revenue.primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(revenue.comparativeAmount()).isEqualByComparingTo("100.00");
        assertThat(netProfit.primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(netProfit.comparativeAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void mixedVoucherIsConservativelyOperatingEvenWhenItIncludesProfitAccount() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("mixed profit account voucher", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID profitId = id("select id from ledger_account where ledger_id = ? and code = '3103'", ledgerId);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher mixed = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Mixed voucher",
                List.of(line(revenueId, "DEBIT", "100"), line(profitId, "CREDIT", "50"),
                        line(cashId, "CREDIT", "50"))));

        assertThat(accountingRole(mixed.id())).isEqualTo("OPERATING");
    }

    @Test
    void configuredProfitAccountIsUsedForManualTransferClassification() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("configured profit transfer", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        UUID periodId = id("select id from accounting_period where ledger_id = ? and period_code = '2026-01'", ledgerId);
        UUID cashId = id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId);
        UUID revenueId = id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId);
        UUID configuredProfit = ledgerService.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("3999", "自定义本年利润", "EQUITY", "CREDIT")).id();
        jdbcTemplate.update("insert into period_closing_setting (ledger_id, profit_account_id) values (?, ?)",
                ledgerId, configuredProfit);

        voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "记", "1", "Revenue",
                List.of(line(cashId, "DEBIT", "100"), line(revenueId, "CREDIT", "100"))));
        VoucherResponses.Voucher transfer = voucherService.create(userId, ledgerId, new VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 31), "记", "2", "Configured closing",
                List.of(line(revenueId, "DEBIT", "100"), line(configuredProfit, "CREDIT", "100"))));

        assertThat(accountingRole(transfer.id())).isEqualTo("PROFIT_LOSS_TRANSFER");
    }

    private void applyProjection(UUID ledgerId) {
        for (int attempt = 0; attempt < 50 && !projection.status(ledgerId, "2026-01").fresh(); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, "2026-01").fresh()).isTrue();
    }

    private String accountingRole(UUID voucherId) {
        return jdbcTemplate.queryForObject("select accounting_role from voucher where id = ?", String.class, voucherId);
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE, "report");
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbcTemplate.queryForObject(sql, UUID.class, ledgerId);
    }
}
