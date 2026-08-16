package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * End-to-end statutory cash flow statement (会小企 03 表): SME/CNY only, two
 * fixed columns, twenty-two rows, ten checks, formula metadata and structured
 * data completeness warnings for historical unclassified cash lines.
 */
@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class CashFlowStatutoryReportTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private ReportingService reportingService;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BalanceProjectionRepository projection;

    @Test
    void cashFlowStatementReturnsTwentyTwoRowsTwoColumnsAndPassingChecks() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;
        UUID periodOne = period(fixture.ledgerId, "2026-01");
        UUID periodTwo = period(fixture.ledgerId, "2026-02");
        // January: capital inflow 100, interest outflow 20.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                periodOne, LocalDate.of(2026, 1, 10), "记", "1", "capital",
                List.of(line(fixture.cash, "DEBIT", "100.00", item(fixture.ledgerId, "SME_CF_15_CAPITAL_RECEIPTS")),
                        line(fixture.capital, "CREDIT", "100.00", null))));
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                periodOne, LocalDate.of(2026, 1, 11), "记", "2", "interest",
                List.of(line(fixture.expense, "DEBIT", "20.00", null),
                        line(fixture.bank, "CREDIT", "20.00", item(fixture.ledgerId, "SME_CF_17_INTEREST_PAYMENTS")))));
        // February: sales inflow 300, purchase outflow 200.
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                periodTwo, LocalDate.of(2026, 2, 10), "记", "3", "sales",
                List.of(line(fixture.cash, "DEBIT", "300.00", item(fixture.ledgerId, "SME_CF_01_SALES_RECEIPTS")),
                        line(fixture.revenue, "CREDIT", "300.00", null))));
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                periodTwo, LocalDate.of(2026, 2, 11), "记", "4", "purchase",
                List.of(line(fixture.inventory, "DEBIT", "200.00", null),
                        line(fixture.bank, "CREDIT", "200.00", item(fixture.ledgerId, "SME_CF_03_PURCHASE_PAYMENTS")))));
        applyProjection(fixture.ledgerId);

        StatutoryReportResponses.Statement statement = reportingService.statutoryStatement(
                user, fixture.ledgerId, "cash-flow", "2026-02");

        assertThat(statement.reportType()).isEqualTo("cash-flow");
        assertThat(statement.templateCode()).isEqualTo("SME-2011-17");
        assertThat(statement.standardCode()).isEqualTo("SME");
        assertThat(statement.standardVersion()).isEqualTo("2011-17");
        assertThat(statement.periodCode()).isEqualTo("2026-02");
        assertThat(statement.primaryColumn()).isEqualTo("本年累计金额");
        assertThat(statement.comparativeColumn()).isEqualTo("本月金额");
        assertThat(statement.formulaCode()).isEqualTo("CASH_FLOW");
        assertThat(statement.formulaVersion()).isEqualTo(1);
        long rows = statement.groups().stream()
                .mapToLong(group -> group.lines().size()).sum();
        assertThat(rows).isEqualTo(22);
        assertThat(statement.groups()).extracting(StatutoryReportResponses.Group::key)
                .containsExactly("OPERATING", "INVESTING", "FINANCING", "BALANCES");

        StatutoryReportResponses.Line operatingNet = line(statement, "cf-7");
        assertThat(operatingNet.primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(operatingNet.comparativeAmount()).isEqualByComparingTo("100.00");
        StatutoryReportResponses.Line financingNet = line(statement, "cf-19");
        assertThat(financingNet.primaryAmount()).isEqualByComparingTo("80.00");
        assertThat(financingNet.comparativeAmount()).isEqualByComparingTo("0.00");
        StatutoryReportResponses.Line netIncrease = line(statement, "cf-20");
        assertThat(netIncrease.primaryAmount()).isEqualByComparingTo("180.00");
        assertThat(netIncrease.comparativeAmount()).isEqualByComparingTo("100.00");
        // 期初现金余额: 主列 = 年初 (0), 本月 = 2026-01 期末 (80).
        StatutoryReportResponses.Line opening = line(statement, "cf-21");
        assertThat(opening.primaryAmount()).isEqualByComparingTo("0.00");
        assertThat(opening.comparativeAmount()).isEqualByComparingTo("80.00");
        StatutoryReportResponses.Line closing = line(statement, "cf-22");
        assertThat(closing.primaryAmount()).isEqualByComparingTo("180.00");
        assertThat(closing.comparativeAmount()).isEqualByComparingTo("180.00");

        assertThat(statement.checks()).hasSize(10);
        assertThat(statement.checks()).allMatch(StatutoryReportResponses.Check::passed);
        assertThat(statement.dataQuality().status()).isEqualTo("COMPLETE");
        assertThat(statement.dataQuality().primaryUnclassifiedLineCount()).isZero();
        assertThat(statement.dataQuality().comparativeUnclassifiedLineCount()).isZero();
        assertThat(statement.dataQuality().samples()).isEmpty();
    }

    @Test
    void historicalUnclassifiedCashLinesYieldIncompleteQualityWithSamples() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;
        // 先按完整分类过账（发布投影事件），再把现金行项目置空，模拟分类要求上线前
        // 已过账的历史未分类数据：余额投影不受影响，现金流项目行按零处理。
        VoucherResponses.Voucher unclassified = voucherService.create(user, fixture.ledgerId,
                new VoucherRequests.Create(fixture.periodId, LocalDate.of(2026, 1, 6), "记", "1",
                        "unclassified", List.of(
                        line(fixture.cash, "DEBIT", "50.00", item(fixture.ledgerId, "SME_CF_01_SALES_RECEIPTS")),
                        line(fixture.revenue, "CREDIT", "50.00", null))));
        jdbc.update("""
                update voucher_line set cash_flow_item_id = null
                where voucher_id = ? and account_id = ?
                """, unclassified.id(), fixture.cash);
        applyProjection(fixture.ledgerId);

        StatutoryReportResponses.Statement statement = reportingService.statutoryStatement(
                user, fixture.ledgerId, "cash-flow", "2026-01");

        assertThat(statement.dataQuality().status()).isEqualTo("INCOMPLETE");
        assertThat(statement.dataQuality().primaryUnclassifiedVoucherCount()).isEqualTo(1);
        assertThat(statement.dataQuality().primaryUnclassifiedLineCount()).isEqualTo(1);
        assertThat(statement.dataQuality().comparativeUnclassifiedVoucherCount()).isEqualTo(1);
        assertThat(statement.dataQuality().comparativeUnclassifiedLineCount()).isEqualTo(1);
        assertThat(statement.dataQuality().samples()).hasSize(1);
        assertThat(statement.dataQuality().samples().get(0).reason()).isEqualTo("ITEM_MISSING");
        assertThat(statement.dataQuality().samples().get(0).voucherNumber()).isEqualTo("1");
        // 金额按零处理：现金流项目行不读数，但行 21/22 为实际现金账户余额。
        assertThat(line(statement, "cf-1").primaryAmount()).isEqualByComparingTo("0.00");
        assertThat(line(statement, "cf-22").primaryAmount()).isEqualByComparingTo("50.00");
        // 数据缺失时仍执行勾稽检查；未分类现金使 行22 ≠ 行20 + 行21。
        assertThat(statement.checks()).extracting(StatutoryReportResponses.Check::key)
                .contains("CF_CLOSING_BALANCE");
        assertThat(statement.checks()).filteredOn(check -> "CF_CLOSING_BALANCE".equals(check.key()))
                .anyMatch(check -> !check.passed());
    }

    @Test
    void balanceSheetAndIncomeStatementKeepCompleteQualityAndUnchangedRows() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;
        voucherService.create(user, fixture.ledgerId, new VoucherRequests.Create(
                fixture.periodId, LocalDate.of(2026, 1, 6), "记", "1", "capital",
                List.of(line(fixture.cash, "DEBIT", "100.00", item(fixture.ledgerId, "SME_CF_15_CAPITAL_RECEIPTS")),
                        line(fixture.capital, "CREDIT", "100.00", null))));
        applyProjection(fixture.ledgerId);

        StatutoryReportResponses.Statement balanceSheet = reportingService.statutoryStatement(
                user, fixture.ledgerId, "balance-sheet", "2026-01");
        long numberedBalanceRows = balanceSheet.groups().stream()
                .flatMap(group -> group.lines().stream())
                .filter(row -> row.lineNo() > 0)
                .count();
        assertThat(numberedBalanceRows).isEqualTo(53);
        assertThat(balanceSheet.dataQuality().status()).isEqualTo("COMPLETE");
        assertThat(balanceSheet.dataQuality().samples()).isEmpty();
        assertThat(balanceSheet.checks()).allMatch(StatutoryReportResponses.Check::passed);

        StatutoryReportResponses.Statement income = reportingService.statutoryStatement(
                user, fixture.ledgerId, "income-statement", "2026-01");
        assertThat(income.dataQuality().status()).isEqualTo("COMPLETE");
        assertThat(line(income, "is-32").primaryAmount()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsUnsupportedStandardCurrencyAndUnknownReport() {
        Fixture fixture = newFixture();
        UUID user = fixture.userId;
        UUID casLedger = ledgerService.create(new CurrentUserResolver.ResolvedUser(user, "cas", user.toString()),
                new LedgerRequests.Create("cas", "CAS", "2006-18", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        assertThatThrownBy(() -> reportingService.statutoryStatement(user, casLedger, "cash-flow", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_REPORT_UNSUPPORTED_STANDARD"));
        assertThatThrownBy(() -> reportingService.statutoryStatement(user, fixture.ledgerId, "unknown", "2026-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("STATUTORY_REPORT_NOT_FOUND"));
        assertThatThrownBy(() -> reportingService.statutoryStatement(user, fixture.ledgerId, "cash-flow", "2027-01"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("PERIOD_NOT_FOUND"));
    }

    private Fixture newFixture() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "cf-stmt",
                        userId.toString()),
                new LedgerRequests.Create("cf-statement", "SME", "v1", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        return new Fixture(userId, ledgerId,
                period(ledgerId, "2026-01"),
                id("select id from ledger_account where ledger_id = ? and code = '1001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '1002'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '3001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '5001'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '1403'", ledgerId),
                id("select id from ledger_account where ledger_id = ? and code = '5603'", ledgerId));
    }

    private StatutoryReportResponses.Line line(StatutoryReportResponses.Statement statement, String key) {
        return statement.groups().stream()
                .flatMap(group -> group.lines().stream())
                .filter(row -> key.equals(row.key())).findFirst().orElseThrow();
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount, UUID itemId) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE,
                "cf-statement", itemId, null, null, null);
    }

    private UUID item(UUID ledgerId, String code) {
        return jdbc.queryForObject(
                "select id from cash_flow_item where ledger_id = ? and code = ?", UUID.class, ledgerId, code);
    }

    private UUID period(UUID ledgerId, String periodCode) {
        return jdbc.queryForObject(
                "select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, periodCode);
    }

    private UUID id(String sql, UUID ledgerId) {
        return jdbc.queryForObject(sql, UUID.class, ledgerId);
    }

    private void applyProjection(UUID ledgerId) {
        for (int attempt = 0; attempt < 100
                && !(projection.status(ledgerId, "2026-01").fresh()
                        && projection.status(ledgerId, "2026-02").fresh()); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, "2026-01").fresh()).isTrue();
        assertThat(projection.status(ledgerId, "2026-02").fresh()).isTrue();
    }

    private record Fixture(UUID userId, UUID ledgerId, UUID periodId, UUID cash, UUID bank,
                           UUID capital, UUID revenue, UUID inventory, UUID expense) {
    }
}
