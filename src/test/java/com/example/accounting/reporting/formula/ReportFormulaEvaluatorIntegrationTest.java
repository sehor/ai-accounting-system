package com.example.accounting.reporting.formula;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import com.example.accounting.reporting.PeriodRange;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Equivalence of the new evaluator with the pre-cutover calculators on a real
 * ledger: SME fixed lines must match the legacy statutory calculator and CAS
 * detail rows must match the legacy category-based statements.
 */
@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class ReportFormulaEvaluatorIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private ReportingService reporting;

    @Autowired
    private ReportingRepository reports;

    @Autowired
    private ReportFormulaRepository formulas;

    @Autowired
    private ReportFormulaEvaluator evaluator;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BalanceProjectionRepository projection;

    private final FormulaParser parser = new FormulaParser();

    /** Detailed cash flow item attached to test lines so external cash vouchers stay classified. */
    private UUID defaultCashItem;

    @Test
    void smeFixedLinesMatchTheLegacyStatutoryCalculator() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1", "equivalence-sme");
        UUID january = periodId(ledgerId, "2026-01");
        UUID february = periodId(ledgerId, "2026-02");
        UUID cash = accountId(ledgerId, "1001");
        UUID capital = accountId(ledgerId, "3001");
        UUID revenue = accountId(ledgerId, "5001");
        vouchers.create(userId, ledgerId, new VoucherRequests.Create(
                january, LocalDate.of(2026, 1, 15), "GENERAL", "1", "Jan revenue",
                List.of(line(cash, "DEBIT", "100"), line(revenue, "CREDIT", "100"))));
        vouchers.create(userId, ledgerId, new VoucherRequests.Create(
                february, LocalDate.of(2026, 2, 15), "GENERAL", "2", "Feb capital",
                List.of(line(cash, "DEBIT", "50"), line(capital, "CREDIT", "50"))));
        applyProjection(ledgerId, "2026-02");

        for (String reportType : List.of("balance-sheet", "income-statement")) {
            String code = "balance-sheet".equals(reportType) ? "BALANCE_SHEET" : "INCOME_STATEMENT";
            ReportFormulaDefinition definition = parser.parse(
                    formulas.findSnapshot(ledgerId, code).orElseThrow().formulaJson());
            ReportFormulaEvaluator.FixedLinesMetadata metadata =
                    new ReportFormulaEvaluator.FixedLinesMetadata(reportType, "SME", "2011-17",
                            "2026-02", "期末余额", "年初余额");

            StatutoryReportResponses.Statement evaluated = "balance-sheet".equals(reportType)
                    ? evaluateBalanceSheet(ledgerId, definition, metadata)
                    : evaluateIncomeStatement(ledgerId, definition, metadata);
            StatutoryReportResponses.Statement legacy = reporting.statutoryStatement(
                    userId, ledgerId, reportType, "2026-02");

            Map<String, StatutoryReportResponses.Line> evaluatedLines = evaluated.groups().stream()
                    .flatMap(group -> group.lines().stream())
                    .collect(Collectors.toMap(StatutoryReportResponses.Line::key, line -> line));
            Map<String, StatutoryReportResponses.Line> legacyLines = legacy.groups().stream()
                    .flatMap(group -> group.lines().stream())
                    .collect(Collectors.toMap(StatutoryReportResponses.Line::key, line -> line));
            assertThat(evaluatedLines.keySet()).isEqualTo(legacyLines.keySet());
            for (String key : evaluatedLines.keySet()) {
                assertThat(evaluatedLines.get(key).primaryAmount())
                        .as("%s %s primary", reportType, key)
                        .isEqualByComparingTo(legacyLines.get(key).primaryAmount());
                assertThat(evaluatedLines.get(key).comparativeAmount())
                        .as("%s %s comparative", reportType, key)
                        .isEqualByComparingTo(legacyLines.get(key).comparativeAmount());
            }
            assertThat(evaluated.checks()).extracting(StatutoryReportResponses.Check::key)
                    .isEqualTo(legacy.checks().stream().map(StatutoryReportResponses.Check::key).toList());
        }
    }

    @Test
    void casDetailRowsMatchTheLegacyCategoryStatements() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "CAS", "2006-18", "equivalence-cas");
        UUID january = periodId(ledgerId, "2026-01");
        UUID cash = accountId(ledgerId, "1001");
        UUID capital = accountId(ledgerId, "4001");
        vouchers.create(userId, ledgerId, new VoucherRequests.Create(
                january, LocalDate.of(2026, 1, 15), "GENERAL", "1", "CAS capital",
                List.of(line(cash, "DEBIT", "70"), line(capital, "CREDIT", "70"))));
        applyProjection(ledgerId, "2026-01");

        List<FormulaAccountAmount> source = reports.formulaAccountAmounts(
                ledgerId, PeriodRange.single("2026-01"), false);
        ReportFormulaDefinition balanceSheet = parser.parse(
                formulas.findSnapshot(ledgerId, "BALANCE_SHEET").orElseThrow().formulaJson());
        ReportResponses.Statement evaluated = evaluator.evaluateAccountDetail(ledgerId, balanceSheet, source);
        ReportResponses.Statement legacy = reporting.balanceSheet(userId, ledgerId, "2026-01");

        assertThat(evaluated.totalLines()).isEqualTo(legacy.totalLines());
        assertThat(evaluated.lines()).extracting(ReportResponses.StatementLine::code)
                .isEqualTo(legacy.lines().stream().map(ReportResponses.StatementLine::code).toList());
        assertThat(evaluated.lines()).extracting(ReportResponses.StatementLine::amount)
                .containsExactlyElementsOf(
                        legacy.lines().stream().map(ReportResponses.StatementLine::amount).toList());
    }

    private StatutoryReportResponses.Statement evaluateBalanceSheet(
            UUID ledgerId, ReportFormulaDefinition definition,
            ReportFormulaEvaluator.FixedLinesMetadata metadata) {
        String firstPeriod = reports.firstPeriodOfYear(ledgerId, "2026-02");
        List<FormulaAccountAmount> primary = reports.formulaAccountAmounts(
                ledgerId, PeriodRange.single("2026-02"), false);
        List<FormulaAccountAmount> comparative = reports.formulaAccountAmounts(
                ledgerId, PeriodRange.single(firstPeriod), false);
        return evaluator.evaluateFixedLines(ledgerId, definition, primary, comparative, metadata);
    }

    private StatutoryReportResponses.Statement evaluateIncomeStatement(
            UUID ledgerId, ReportFormulaDefinition definition,
            ReportFormulaEvaluator.FixedLinesMetadata metadata) {
        String firstPeriod = reports.firstPeriodOfYear(ledgerId, "2026-02");
        List<FormulaAccountAmount> primary = reports.formulaAccountAmounts(
                ledgerId, new PeriodRange(firstPeriod, "2026-02"), true);
        List<FormulaAccountAmount> comparative = reports.formulaAccountAmounts(
                ledgerId, PeriodRange.single("2026-02"), true);
        return evaluator.evaluateFixedLines(ledgerId, definition, primary, comparative, metadata);
    }

    private void applyProjection(UUID ledgerId, String period) {
        for (int attempt = 0; attempt < 50 && !projection.status(ledgerId, period).fresh(); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, period).fresh()).isTrue();
    }

    private UUID createLedger(UUID userId, String standardCode, String standardVersion, String name) {
        CurrentUserResolver.ResolvedUser user =
                new CurrentUserResolver.ResolvedUser(userId, "test", UUID.randomUUID().toString());
        return ledgers.create(user, new LedgerRequests.Create(name,
                standardCode, standardVersion, "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }

    private UUID periodId(UUID ledgerId, String periodCode) {
        return jdbc.queryForObject("select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, periodCode);
    }

    private UUID accountId(UUID ledgerId, String code) {
        List<UUID> items = jdbc.queryForList(
                "select id from cash_flow_item where ledger_id = ? and code = 'SME_CF_01_SALES_RECEIPTS'",
                UUID.class, ledgerId);
        defaultCashItem = items.isEmpty() ? null : items.getFirst();
        return jdbc.queryForObject("select id from ledger_account where ledger_id = ? and code = ?",
                UUID.class, ledgerId, code);
    }

    private VoucherRequests.Line line(UUID accountId, String side, String amount) {
        return new VoucherRequests.Line(accountId, side, "CNY", new BigDecimal(amount), BigDecimal.ONE,
                "equivalence", defaultCashItem, null, null, null);
    }
}
