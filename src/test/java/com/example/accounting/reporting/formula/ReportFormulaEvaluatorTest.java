package com.example.accounting.reporting.formula;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.ColumnPolicy;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.CheckColumn;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaCheck;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReportFormulaEvaluatorTest {

    private final UUID cashLeaf = UUID.randomUUID();
    private final UUID bankLeaf = UUID.randomUUID();
    private final UUID parentLeafA = UUID.randomUUID();
    private final UUID parentLeafB = UUID.randomUUID();

    private final FakeResolver resolver = new FakeResolver();

    @Test
    void evaluatesFixedLinesWithPrimaryAndComparativeColumnsAndChecks() {
        ReportFormulaEvaluator evaluator = new ReportFormulaEvaluator(resolver);
        resolver.byKey.put("ASSET.CASH", Set.of(cashLeaf));
        ReportFormulaDefinition definition = new ReportFormulaDefinition(
                1, "FIXED_LINES", "BALANCE_SHEET", "SME-2011-17",
                new ColumnPolicy(AmountBasis.CLOSING, AmountBasis.OPENING),
                List.of(new FormulaGroup("LEFT", "资产", List.of(
                        new FormulaLine("bs-1", 1, 0, "DETAIL", "货币资金",
                                new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of(
                                        new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH")))),
                        new FormulaLine("bs-2", 2, 0, "TOTAL", "资产合计",
                                new LinearCombinationExpression(List.of(
                                        new LineComponent("bs-1", 1))))))),
                List.of(), List.of(
                        new FormulaCheck("ASSET_EQUATION", "资产平衡",
                                "bs-2", "bs-2", CheckColumn.PRIMARY),
                        new FormulaCheck("OPENING_EQUATION", "年初差额",
                                "bs-2", "missing", CheckColumn.COMPARATIVE)));
        List<FormulaAccountAmount> primary = List.of(
                amount(cashLeaf, "1001", "库存现金", "ASSET.CASH", "CURRENT_ASSET",
                        "10.00", "2.00", "10.00"),
                amount(bankLeaf, "1002", "银行存款", "ASSET.BANK_DEPOSIT", "CURRENT_ASSET",
                        "0.00", "0.00", "0.00"));
        List<FormulaAccountAmount> comparative = List.of(
                amount(cashLeaf, "1001", "库存现金", "ASSET.CASH", "CURRENT_ASSET",
                        "5.00", "0.00", "5.00"));

        StatutoryReportResponses.Statement statement = evaluator.evaluateFixedLines(
                UUID.randomUUID(), definition, primary, comparative,
                new ReportFormulaEvaluator.FixedLinesMetadata(
                        "balance-sheet", "SME", "2011-17", "2026-02", "期末余额", "年初余额"));

        assertThat(statement.templateCode()).isEqualTo("SME-2011-17");
        assertThat(statement.groups()).hasSize(1);
        List<StatutoryReportResponses.Line> rows = statement.groups().get(0).lines();
        assertThat(rows.get(0).primaryAmount()).isEqualByComparingTo("10.00");
        assertThat(rows.get(0).comparativeAmount()).isEqualByComparingTo("5.00");
        assertThat(rows.get(1).primaryAmount()).isEqualByComparingTo("10.00");
        assertThat(rows.get(1).comparativeAmount()).isEqualByComparingTo("5.00");
        assertThat(statement.checks()).first().satisfies(check -> {
            assertThat(check.key()).isEqualTo("ASSET_EQUATION");
            assertThat(check.passed()).isTrue();
            assertThat(check.difference()).isEqualByComparingTo("0.00");
        });
        assertThat(statement.checks()).element(1).satisfies(check -> {
            assertThat(check.key()).isEqualTo("OPENING_EQUATION");
            assertThat(check.passed()).isFalse();
            assertThat(check.difference()).isEqualByComparingTo("5.00");
        });
    }

    @Test
    void accountAmountRespectsSideAndBasis() {
        ReportFormulaEvaluator evaluator = new ReportFormulaEvaluator(resolver);
        ReportFormulaDefinition definition = new ReportFormulaDefinition(
                1, "FIXED_LINES", "BALANCE_SHEET", "SME-2011-17",
                new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.NONE),
                List.of(new FormulaGroup("F", "利润表", List.of(
                        new FormulaLine("is-1", 1, 0, "DETAIL", "营业收入",
                                new AccountAmountExpression("ACCOUNT_ACTIVITY", "CREDIT", List.of(
                                        new AccountReference("STANDARD_ACCOUNT_KEY", "INCOME.MAIN_BUSINESS_REVENUE"),
                                        new AccountReference("ACCOUNT_ID", parentLeafA.toString()))))))),
                List.of(), List.of());
        resolver.byKey.put("INCOME.MAIN_BUSINESS_REVENUE", Set.of(cashLeaf));
        resolver.byId.put(parentLeafA, Set.of(parentLeafA, parentLeafB));

        List<FormulaAccountAmount> source = List.of(
                creditAmount(cashLeaf, "5001", "主营收入", "INCOME.MAIN_BUSINESS_REVENUE", "OPERATING_REVENUE",
                        "0.00", "8.00", "8.00"),
                creditAmount(parentLeafA, "6001", "其他收入", null, "OTHER_INCOME",
                        "0.00", "3.00", "3.00"),
                creditAmount(parentLeafB, "6002", "其他收入2", null, "OTHER_INCOME",
                        "0.00", "1.00", "1.00"));

        StatutoryReportResponses.Statement statement = evaluator.evaluateFixedLines(
                UUID.randomUUID(), definition, source, List.of(),
                new ReportFormulaEvaluator.FixedLinesMetadata(
                        "income-statement", "SME", "2011-17", "2026-02", "本年累计金额", "本月金额"));

        // ACTIVITY basis reads periodDebit/periodCredit; CREDIT side is credit - debit.
        assertThat(statement.groups().get(0).lines().get(0).primaryAmount())
                .isEqualByComparingTo("12.00");
    }

    @Test
    void accountDetailMatchesCategoriesAndDedupsSameSideRules() {
        ReportFormulaEvaluator evaluator = new ReportFormulaEvaluator(resolver);
        ReportFormulaDefinition definition = new ReportFormulaDefinition(
                1, "ACCOUNT_DETAIL", "BALANCE_SHEET", "CAS-2006-18",
                new ColumnPolicy(AmountBasis.CLOSING, AmountBasis.NONE),
                List.of(),
                List.of(
                        new DetailRule("D1", "DEBIT", List.of("CURRENT_ASSET"), List.of(
                                new AccountReference("ACCOUNT_ID", parentLeafA.toString()))),
                        new DetailRule("D2", "DEBIT", List.of("CURRENT_ASSET"), List.of()),
                        new DetailRule("C1", "CREDIT", List.of("EQUITY"), List.of())),
                List.of());
        resolver.byId.put(parentLeafA, Set.of(parentLeafA));

        List<FormulaAccountAmount> source = List.of(
                amount(cashLeaf, "1001", "库存现金", "ASSET.CASH", "CURRENT_ASSET",
                        "0.00", "0.00", "30.00"),
                amount(parentLeafA, "1122", "应收账款", "ASSET.ACCOUNTS_RECEIVABLE", "CURRENT_ASSET",
                        "0.00", "0.00", "20.00"),
                amount(parentLeafB, "3001", "实收资本", "EQUITY.PAID_IN_CAPITAL", "EQUITY",
                        "0.00", "0.00", "50.00"));

        ReportResponses.Statement statement = evaluator.evaluateAccountDetail(
                UUID.randomUUID(), definition, source);

        assertThat(statement.totalLines()).isEqualTo(3);
        assertThat(statement.lines()).extracting(ReportResponses.StatementLine::code)
                .containsExactly("1001", "1122", "3001");
        assertThat(statement.lines()).extracting(ReportResponses.StatementLine::amount)
                .containsExactly(new BigDecimal("30.00"), new BigDecimal("20.00"),
                        new BigDecimal("-50.00"));
    }

    @Test
    void evaluatesCashFlowDirectionsNetAndOpeningClosingBasis() {
        ReportFormulaEvaluator evaluator = new ReportFormulaEvaluator(resolver);
        resolver.byKey.put("ASSET.CASH", Set.of(cashLeaf));
        resolver.byKey.put("ASSET.BANK_DEPOSIT", Set.of(bankLeaf));
        List<AccountReference> cashAccounts = List.of(
                new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH"),
                new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.BANK_DEPOSIT"));
        ReportFormulaDefinition definition = new ReportFormulaDefinition(
                1, "FIXED_LINES", "CASH_FLOW", "SME-2011-17",
                new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.ACTIVITY),
                List.of(new FormulaGroup("OPERATING", "经营活动", List.of(
                        new FormulaLine("cf-1", 1, 0, "DETAIL", "销售收到的现金",
                                new ReportFormulaDefinition.CashFlowItemAmountExpression(
                                        ReportFormulaDefinition.CashFlowDirection.INFLOW,
                                        List.of("SME_CF_01_SALES_RECEIPTS"), cashAccounts)),
                        new FormulaLine("cf-3", 3, 0, "DETAIL", "购买支付的现金",
                                new ReportFormulaDefinition.CashFlowItemAmountExpression(
                                        ReportFormulaDefinition.CashFlowDirection.OUTFLOW,
                                        List.of("SME_CF_03_PURCHASE_PAYMENTS"), cashAccounts)),
                        new FormulaLine("cf-7", 7, 0, "TOTAL", "经营净额",
                                new LinearCombinationExpression(List.of(
                                        new LineComponent("cf-1", 1), new LineComponent("cf-3", -1)))))),
                        new FormulaGroup("BALANCES", "余额", List.of(
                                new FormulaLine("cf-20", 20, 0, "TOTAL", "现金净增加额",
                                        new LinearCombinationExpression(List.of(
                                                new LineComponent("cf-7", 1)))),
                                new FormulaLine("cf-21", 21, 0, "DETAIL", "期初现金余额",
                                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT",
                                                cashAccounts, AmountBasis.OPENING)),
                                new FormulaLine("cf-22", 22, 0, "TOTAL", "期末现金余额",
                                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT",
                                                cashAccounts, AmountBasis.CLOSING))))),
                List.of(),
                List.of(
                        new FormulaCheck("CF_NET_INCREASE", "净增加额", "cf-20", null,
                                CheckColumn.PRIMARY, List.of(new LineComponent("cf-7", 1))),
                        new FormulaCheck("CF_CLOSING_BALANCE", "期末余额", "cf-22", null,
                                CheckColumn.COMPARATIVE, List.of(
                                        new LineComponent("cf-20", 1), new LineComponent("cf-21", 1)))));

        CashFlowSource primaryFlows = CashFlowSource.of(
                Map.of("SME_CF_01_SALES_RECEIPTS", decimal("100.00")),
                Map.of("SME_CF_03_PURCHASE_PAYMENTS", decimal("60.00")));
        CashFlowSource comparativeFlows = CashFlowSource.of(
                Map.of("SME_CF_01_SALES_RECEIPTS", decimal("40.00")),
                Map.of("SME_CF_03_PURCHASE_PAYMENTS", decimal("10.00")));
        List<FormulaAccountAmount> primaryBalances = List.of(
                amount(cashLeaf, "1001", "库存现金", "ASSET.CASH", "CURRENT_ASSET",
                        "200.00", "40.00", "240.00"),
                amount(bankLeaf, "1002", "银行存款", "ASSET.BANK_DEPOSIT", "CURRENT_ASSET",
                        "0.00", "0.00", "0.00"));
        List<FormulaAccountAmount> comparativeBalances = List.of(
                amount(cashLeaf, "1001", "库存现金", "ASSET.CASH", "CURRENT_ASSET",
                        "210.00", "30.00", "240.00"),
                amount(bankLeaf, "1002", "银行存款", "ASSET.BANK_DEPOSIT", "CURRENT_ASSET",
                        "0.00", "0.00", "0.00"));

        StatutoryReportResponses.Statement statement = evaluator.evaluateFixedLines(
                UUID.randomUUID(), definition, primaryBalances, comparativeBalances,
                primaryFlows, comparativeFlows,
                new ReportFormulaEvaluator.FixedLinesMetadata(
                        "cash-flow", "SME", "2011-17", "2026-02", "本年累计金额", "本月金额"));

        List<StatutoryReportResponses.Line> operating = statement.groups().get(0).lines();
        assertThat(operating.get(0).primaryAmount()).isEqualByComparingTo("100.00");
        assertThat(operating.get(0).comparativeAmount()).isEqualByComparingTo("40.00");
        assertThat(operating.get(1).primaryAmount()).isEqualByComparingTo("60.00");
        assertThat(operating.get(1).comparativeAmount()).isEqualByComparingTo("10.00");
        assertThat(operating.get(2).primaryAmount()).isEqualByComparingTo("40.00");
        assertThat(operating.get(2).comparativeAmount()).isEqualByComparingTo("30.00");
        List<StatutoryReportResponses.Line> balances = statement.groups().get(1).lines();
        assertThat(balances.get(0).primaryAmount()).isEqualByComparingTo("40.00");
        // 期初现金余额 reads each column's own opening.
        assertThat(balances.get(1).primaryAmount()).isEqualByComparingTo("200.00");
        assertThat(balances.get(1).comparativeAmount()).isEqualByComparingTo("210.00");
        // 期末现金余额 reads each column's own closing.
        assertThat(balances.get(2).primaryAmount()).isEqualByComparingTo("240.00");
        assertThat(balances.get(2).comparativeAmount()).isEqualByComparingTo("240.00");
        assertThat(statement.checks()).hasSize(2);
        assertThat(statement.checks()).allMatch(StatutoryReportResponses.Check::passed);
    }

    @Test
    void cashFlowNetKeepsSignAndRedAmountsOffsetOutflow() {
        ReportFormulaEvaluator evaluator = new ReportFormulaEvaluator(resolver);
        resolver.byKey.put("ASSET.CASH", Set.of(cashLeaf));
        List<AccountReference> cashAccounts = List.of(
                new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH"));
        ReportFormulaDefinition definition = new ReportFormulaDefinition(
                1, "FIXED_LINES", "CASH_FLOW", "SME-2011-17",
                new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.ACTIVITY),
                List.of(new FormulaGroup("INVESTING", "投资活动", List.of(
                        new FormulaLine("cf-10", 10, 0, "DETAIL", "处置资产净额",
                                new ReportFormulaDefinition.CashFlowItemAmountExpression(
                                        ReportFormulaDefinition.CashFlowDirection.NET,
                                        List.of("SME_CF_10_ASSET_DISPOSAL"), cashAccounts)),
                        new FormulaLine("cf-3", 3, 0, "DETAIL", "采购支出",
                                new ReportFormulaDefinition.CashFlowItemAmountExpression(
                                        ReportFormulaDefinition.CashFlowDirection.OUTFLOW,
                                        List.of("SME_CF_03_PURCHASE_PAYMENTS"), cashAccounts))))),
                List.of(), List.of());

        // NET keeps debit minus credit (can be negative); OUTFLOW returns a
        // positive expense and red (negative debit) entries offset it.
        CashFlowSource flows = CashFlowSource.of(
                Map.of("SME_CF_10_ASSET_DISPOSAL", decimal("-5.00"),
                        "SME_CF_03_PURCHASE_PAYMENTS", decimal("-3.00")),
                Map.of("SME_CF_10_ASSET_DISPOSAL", decimal("8.00"),
                        "SME_CF_03_PURCHASE_PAYMENTS", decimal("60.00")));

        StatutoryReportResponses.Statement statement = evaluator.evaluateFixedLines(
                UUID.randomUUID(), definition, List.of(), List.of(), flows, CashFlowSource.empty(),
                new ReportFormulaEvaluator.FixedLinesMetadata(
                        "cash-flow", "SME", "2011-17", "2026-02", "本年累计金额", "本月金额"));

        List<StatutoryReportResponses.Line> rows = statement.groups().get(0).lines();
        assertThat(rows.get(0).primaryAmount()).isEqualByComparingTo("-13.00");
        assertThat(rows.get(1).primaryAmount()).isEqualByComparingTo("63.00");
    }

    @Test
    void evaluatorContainsNoFixedTemplateLineKeys() throws Exception {
        String evaluatorSource = java.nio.file.Files.readString(
                java.nio.file.Path.of("src/main/java/com/example/accounting/reporting/formula/"
                        + "ReportFormulaEvaluator.java"));
        assertThat(evaluatorSource)
                .doesNotContain("bs-30").doesNotContain("bs-53")
                .doesNotContain("is-32").doesNotContain("ASSET_EQUATION");
    }

    private FormulaAccountAmount amount(UUID accountId, String code, String name,
                                        String standardKey, String category,
                                        String opening, String period, String closing) {
        return new FormulaAccountAmount(accountId, code, name, standardKey, category,
                decimal(opening), BigDecimal.ZERO, decimal(period), BigDecimal.ZERO,
                decimal(closing), BigDecimal.ZERO);
    }

    private FormulaAccountAmount creditAmount(UUID accountId, String code, String name,
                                              String standardKey, String category,
                                              String opening, String period, String closing) {
        return new FormulaAccountAmount(accountId, code, name, standardKey, category,
                BigDecimal.ZERO, decimal(opening), BigDecimal.ZERO, decimal(period),
                BigDecimal.ZERO, decimal(closing));
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private static final class FakeResolver extends FormulaAccountResolver {
        private final Map<String, Set<UUID>> byKey = new HashMap<>();
        private final Map<UUID, Set<UUID>> byId = new HashMap<>();

        private FakeResolver() {
            super(null);
        }

        @Override
        public Set<UUID> expandToLeafIds(UUID ledgerId, List<AccountReference> references) {
            Set<UUID> result = new HashSet<>();
            for (AccountReference reference : references) {
                if (ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY.equals(reference.type())) {
                    result.addAll(byKey.getOrDefault(reference.value(), Set.of()));
                } else {
                    result.addAll(byId.getOrDefault(UUID.fromString(reference.value()), Set.of()));
                }
            }
            return result;
        }
    }
}
