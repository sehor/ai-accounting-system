package com.example.accounting.ledger.formula;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.ColumnPolicy;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardFormulaConverterTest {

    private final AccountingStandardCatalog catalog =
            new AccountingStandardCatalog(new ObjectMapper().findAndRegisterModules());
    private final StandardFormulaConverter converter = new StandardFormulaConverter();
    private final FormulaParser parser = new FormulaParser();

    @Test
    void convertsSmeBalanceSheetToFixedLinesWithFiftyThreeRowsAndChecks() {
        ReportFormulaDefinition definition = smeFormula("BALANCE_SHEET");

        assertThat(definition.schemaVersion()).isEqualTo(1);
        assertThat(definition.kind()).isEqualTo("FIXED_LINES");
        assertThat(definition.reportType()).isEqualTo("BALANCE_SHEET");
        assertThat(definition.templateCode()).isEqualTo("SME-2011-17");
        assertThat(definition.columnPolicy())
                .isEqualTo(new ColumnPolicy(AmountBasis.CLOSING, AmountBasis.OPENING));
        assertThat(definition.rules()).isEmpty();
        assertThat(definition.groups()).extracting(ReportFormulaDefinition.FormulaGroup::key)
                .containsExactly("LEFT", "RIGHT");
        long numberedLines = definition.groups().stream()
                .flatMap(group -> group.lines().stream())
                .filter(line -> line.lineNo() > 0)
                .count();
        assertThat(numberedLines).isEqualTo(53);
        assertThat(definition.checks()).extracting(ReportFormulaDefinition.FormulaCheck::code)
                .containsExactly("ASSET_EQUATION", "OPENING_EQUATION");
        assertThat(definition.checks()).allMatch(check ->
                "bs-30".equals(check.leftLineKey()) && "bs-53".equals(check.rightLineKey()));
    }

    @Test
    void convertsSmeIncomeStatementToFixedLinesWithThirtyTwoRows() {
        ReportFormulaDefinition definition = smeFormula("INCOME_STATEMENT");

        assertThat(definition.kind()).isEqualTo("FIXED_LINES");
        assertThat(definition.reportType()).isEqualTo("INCOME_STATEMENT");
        assertThat(definition.columnPolicy())
                .isEqualTo(new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.ACTIVITY));
        assertThat(definition.checks()).isEmpty();
        long numberedLines = definition.groups().stream()
                .flatMap(group -> group.lines().stream())
                .filter(line -> line.lineNo() > 0)
                .count();
        assertThat(numberedLines).isEqualTo(32);
        assertThat(definition.groups()).extracting(ReportFormulaDefinition.FormulaGroup::key)
                .containsExactly("FULL");
    }

    @Test
    void convertsSmeCashFlowToFixedLinesWithTwentyTwoRowsAndTenChecks() {
        ReportFormulaDefinition definition = smeFormula("CASH_FLOW");

        assertThat(definition.kind()).isEqualTo("FIXED_LINES");
        assertThat(definition.reportType()).isEqualTo("CASH_FLOW");
        assertThat(definition.templateCode()).isEqualTo("SME-2011-17");
        assertThat(definition.columnPolicy())
                .isEqualTo(new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.ACTIVITY));
        assertThat(definition.rules()).isEmpty();
        assertThat(definition.groups()).extracting(ReportFormulaDefinition.FormulaGroup::key)
                .containsExactly("OPERATING", "INVESTING", "FINANCING", "BALANCES");
        long numberedLines = definition.groups().stream()
                .flatMap(group -> group.lines().stream())
                .filter(line -> line.lineNo() > 0)
                .count();
        assertThat(numberedLines).isEqualTo(22);
        assertThat(definition.checks()).hasSize(10);
        assertThat(definition.checks()).extracting(ReportFormulaDefinition.FormulaCheck::code)
                .containsExactly("CF_OPERATING_NET", "CF_INVESTING_NET", "CF_FINANCING_NET",
                        "CF_NET_INCREASE", "CF_CLOSING_BALANCE",
                        "CF_OPERATING_NET", "CF_INVESTING_NET", "CF_FINANCING_NET",
                        "CF_NET_INCREASE", "CF_CLOSING_BALANCE");
        List<ReportFormulaDefinition.FormulaLine> lines = definition.groups().stream()
                .flatMap(group -> group.lines().stream())
                .toList();
        ReportFormulaDefinition.FormulaLine sales = lines.stream()
                .filter(line -> "cf-1".equals(line.key())).findFirst().orElseThrow();
        assertThat(sales.expression())
                .isInstanceOf(ReportFormulaDefinition.CashFlowItemAmountExpression.class);
        ReportFormulaDefinition.CashFlowItemAmountExpression salesFlow =
                (ReportFormulaDefinition.CashFlowItemAmountExpression) sales.expression();
        assertThat(salesFlow.direction()).isEqualTo(ReportFormulaDefinition.CashFlowDirection.INFLOW);
        assertThat(salesFlow.itemCodes()).containsExactly("SME_CF_01_SALES_RECEIPTS");
        assertThat(salesFlow.cashAccounts()).extracting(ReportFormulaDefinition.AccountReference::value)
                .containsExactly("ASSET.CASH", "ASSET.BANK_DEPOSIT", "ASSET.OTHER_MONETARY_FUNDS");
        ReportFormulaDefinition.FormulaLine disposal = lines.stream()
                .filter(line -> "cf-10".equals(line.key())).findFirst().orElseThrow();
        assertThat(((ReportFormulaDefinition.CashFlowItemAmountExpression) disposal.expression())
                .direction()).isEqualTo(ReportFormulaDefinition.CashFlowDirection.NET);
        ReportFormulaDefinition.FormulaLine opening = lines.stream()
                .filter(line -> "cf-21".equals(line.key())).findFirst().orElseThrow();
        ReportFormulaDefinition.AccountAmountExpression openingAmount =
                (ReportFormulaDefinition.AccountAmountExpression) opening.expression();
        assertThat(openingAmount.basis()).isEqualTo(AmountBasis.OPENING);
        ReportFormulaDefinition.FormulaLine closing = lines.stream()
                .filter(line -> "cf-22".equals(line.key())).findFirst().orElseThrow();
        assertThat(((ReportFormulaDefinition.AccountAmountExpression) closing.expression()).basis())
                .isEqualTo(AmountBasis.CLOSING);
    }

    @Test
    void convertsCasBalanceSheetToAccountDetailRules() {
        ReportFormulaDefinition definition = casFormula("BALANCE_SHEET");

        assertThat(definition.schemaVersion()).isEqualTo(1);
        assertThat(definition.kind()).isEqualTo("ACCOUNT_DETAIL");
        assertThat(definition.reportType()).isEqualTo("BALANCE_SHEET");
        assertThat(definition.templateCode()).isEqualTo("CAS-2006-18");
        assertThat(definition.columnPolicy())
                .isEqualTo(new ColumnPolicy(AmountBasis.CLOSING, AmountBasis.NONE));
        assertThat(definition.groups()).isEmpty();
        assertThat(definition.checks()).isEmpty();
        assertThat(definition.rules()).extracting(ReportFormulaDefinition.DetailRule::side)
                .containsExactly("DEBIT", "CREDIT");
        assertThat(definition.rules().get(0).categories())
                .containsExactly("CURRENT_ASSET", "NON_CURRENT_ASSET");
        assertThat(definition.rules().get(1).categories())
                .containsExactly("CURRENT_LIABILITY", "NON_CURRENT_LIABILITY", "EQUITY");
    }

    @Test
    void convertsCasIncomeStatementToAccountDetailRules() {
        ReportFormulaDefinition definition = casFormula("INCOME_STATEMENT");

        assertThat(definition.kind()).isEqualTo("ACCOUNT_DETAIL");
        assertThat(definition.columnPolicy())
                .isEqualTo(new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.NONE));
        assertThat(definition.rules()).extracting(ReportFormulaDefinition.DetailRule::side)
                .containsExactly("CREDIT", "DEBIT");
        assertThat(definition.rules().get(0).categories())
                .containsExactly("OPERATING_REVENUE", "OTHER_INCOME");
        assertThat(definition.rules().get(1).categories())
                .containsExactly("COST", "OPERATING_COST_AND_TAX", "OTHER_EXPENSE", "PERIOD_EXPENSE",
                        "INCOME_TAX", "PRIOR_YEAR_ADJUSTMENT");
    }

    @Test
    void allFourFormulasRoundTripThroughTheJsonSchema() {
        for (String standardCode : List.of("SME", "CAS")) {
            AccountingStandard.Package standard = catalog.find(standardCode,
                    "SME".equals(standardCode) ? "2011-17" : "2006-18").orElseThrow();
            for (ReportFormulaDefinition definition : converter.convertAll(standard)) {
                String json = parser.write(definition);
                ReportFormulaDefinition parsed = parser.parse(json);
                assertThat(parsed).isEqualTo(definition);
                assertThat(parsed.schemaVersion()).isEqualTo(1);
            }
        }
    }

    @Test
    void canonicalJsonUsesDiscriminatedExpressionsAndStandardAccountReferences() {
        ReportFormulaDefinition definition = smeFormula("BALANCE_SHEET");
        String json = parser.write(definition);

        assertThat(json).contains("\"schemaVersion\":1");
        assertThat(json).contains("\"type\":\"ACCOUNT_AMOUNT\"");
        assertThat(json).contains("\"type\":\"LINEAR_COMBINATION\"");
        assertThat(json).contains("\"type\":\"STANDARD_ACCOUNT_KEY\"");
        assertThat(json).contains("\"value\":\"ASSET.CASH\"");
        assertThat(json).doesNotContain("\"ACCOUNT_ID\"");
    }

    @Test
    void convertedLinesKeepOperationsSidesAndComponents() {
        ReportFormulaDefinition balanceSheet = smeFormula("BALANCE_SHEET");
        List<ReportFormulaDefinition.FormulaLine> lines = balanceSheet.groups().stream()
                .flatMap(group -> group.lines().stream())
                .toList();

        ReportFormulaDefinition.FormulaLine cash = lines.stream()
                .filter(line -> "bs-1".equals(line.key())).findFirst().orElseThrow();
        assertThat(cash.expression()).isInstanceOf(AccountAmountExpression.class);
        AccountAmountExpression cashAmount = (AccountAmountExpression) cash.expression();
        assertThat(cashAmount.operation()).isEqualTo("ACCOUNT_BALANCE");
        assertThat(cashAmount.side()).isEqualTo("DEBIT");
        assertThat(cashAmount.accounts()).extracting(
                        ReportFormulaDefinition.AccountReference::value)
                .containsExactly("ASSET.CASH", "ASSET.BANK_DEPOSIT", "ASSET.OTHER_MONETARY_FUNDS");

        ReportFormulaDefinition.FormulaLine netFixedAssets = lines.stream()
                .filter(line -> "bs-20".equals(line.key())).findFirst().orElseThrow();
        assertThat(netFixedAssets.expression()).isInstanceOf(LinearCombinationExpression.class);
        assertThat(((LinearCombinationExpression) netFixedAssets.expression()).components())
                .extracting(ReportFormulaDefinition.LineComponent::lineKey, ReportFormulaDefinition.LineComponent::factor)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("bs-18", 1),
                        org.assertj.core.groups.Tuple.tuple("bs-19", -1));

        ReportFormulaDefinition definition = smeFormula("INCOME_STATEMENT");
        ReportFormulaDefinition.FormulaLine operatingRevenue = definition.groups().get(0).lines().stream()
                .filter(line -> "is-1".equals(line.key())).findFirst().orElseThrow();
        assertThat(((AccountAmountExpression) operatingRevenue.expression()).operation())
                .isEqualTo("ACCOUNT_ACTIVITY");
    }

    private ReportFormulaDefinition smeFormula(String code) {
        AccountingStandard.Package sme = catalog.find("SME", "2011-17").orElseThrow();
        return converter.convert(sme, sme.formulas().stream()
                .filter(formula -> code.equals(formula.code())).findFirst().orElseThrow());
    }

    private ReportFormulaDefinition casFormula(String code) {
        AccountingStandard.Package cas = catalog.find("CAS", "2006-18").orElseThrow();
        return converter.convert(cas, cas.formulas().stream()
                .filter(formula -> code.equals(formula.code())).findFirst().orElseThrow());
    }
}
