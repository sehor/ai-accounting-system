package com.example.accounting.ledger.formula;

import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.ColumnPolicy;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaCheck;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Converts the legacy SME/CAS standard package definitions into the canonical
 * {@link ReportFormulaDefinition} schema.  SME statutory definitions become
 * {@code FIXED_LINES} preserving the existing 53/32 rows, period semantics and
 * formula results; CAS category arrays become {@code ACCOUNT_DETAIL} rules.
 * Standard templates only ever reference {@code STANDARD_ACCOUNT_KEY}, never
 * concrete ledger account ids.
 */
@Component
public class StandardFormulaConverter {

    private static final String SME_TEMPLATE_PREFIX = "SME-2011-17";
    private static final String CAS_TEMPLATE_PREFIX = "CAS-2006-18";
    private static final List<String> LEGACY_CATEGORY_FIELDS = List.of(
            "debitCategories", "creditCategories", "revenueCategories", "expenseCategories");

    private static final List<FormulaCheck> BALANCE_CHECKS = List.of(
            new FormulaCheck("ASSET_EQUATION", "期末资产总计=负债和所有者权益总计",
                    "bs-30", "bs-53", ReportFormulaDefinition.CheckColumn.PRIMARY),
            new FormulaCheck("OPENING_EQUATION", "年初资产总计=负债和所有者权益总计",
                    "bs-30", "bs-53", ReportFormulaDefinition.CheckColumn.COMPARATIVE));

    /** 会小企 03 表勾稽关系，主列与第二列各 5 项，共 10 项。 */
    private static final List<FormulaCheck> CASH_FLOW_CHECKS = List.of(
            cashFlowCheck("CF_OPERATING_NET", "经营活动净额=收入-支出", "cf-7",
                    ReportFormulaDefinition.CheckColumn.PRIMARY,
                    List.of(new LineComponent("cf-1", 1), new LineComponent("cf-2", 1),
                            new LineComponent("cf-3", -1), new LineComponent("cf-4", -1),
                            new LineComponent("cf-5", -1), new LineComponent("cf-6", -1))),
            cashFlowCheck("CF_INVESTING_NET", "投资活动净额=收入-支出", "cf-13",
                    ReportFormulaDefinition.CheckColumn.PRIMARY,
                    List.of(new LineComponent("cf-8", 1), new LineComponent("cf-9", 1),
                            new LineComponent("cf-10", 1), new LineComponent("cf-11", -1),
                            new LineComponent("cf-12", -1))),
            cashFlowCheck("CF_FINANCING_NET", "筹资活动净额=收入-支出", "cf-19",
                    ReportFormulaDefinition.CheckColumn.PRIMARY,
                    List.of(new LineComponent("cf-14", 1), new LineComponent("cf-15", 1),
                            new LineComponent("cf-16", -1), new LineComponent("cf-17", -1),
                            new LineComponent("cf-18", -1))),
            cashFlowCheck("CF_NET_INCREASE", "现金净增加额=三类净额之和", "cf-20",
                    ReportFormulaDefinition.CheckColumn.PRIMARY,
                    List.of(new LineComponent("cf-7", 1), new LineComponent("cf-13", 1),
                            new LineComponent("cf-19", 1))),
            cashFlowCheck("CF_CLOSING_BALANCE", "期末现金余额=净增加额+期初余额", "cf-22",
                    ReportFormulaDefinition.CheckColumn.PRIMARY,
                    List.of(new LineComponent("cf-20", 1), new LineComponent("cf-21", 1))),
            cashFlowCheck("CF_OPERATING_NET", "经营活动净额=收入-支出", "cf-7",
                    ReportFormulaDefinition.CheckColumn.COMPARATIVE,
                    List.of(new LineComponent("cf-1", 1), new LineComponent("cf-2", 1),
                            new LineComponent("cf-3", -1), new LineComponent("cf-4", -1),
                            new LineComponent("cf-5", -1), new LineComponent("cf-6", -1))),
            cashFlowCheck("CF_INVESTING_NET", "投资活动净额=收入-支出", "cf-13",
                    ReportFormulaDefinition.CheckColumn.COMPARATIVE,
                    List.of(new LineComponent("cf-8", 1), new LineComponent("cf-9", 1),
                            new LineComponent("cf-10", 1), new LineComponent("cf-11", -1),
                            new LineComponent("cf-12", -1))),
            cashFlowCheck("CF_FINANCING_NET", "筹资活动净额=收入-支出", "cf-19",
                    ReportFormulaDefinition.CheckColumn.COMPARATIVE,
                    List.of(new LineComponent("cf-14", 1), new LineComponent("cf-15", 1),
                            new LineComponent("cf-16", -1), new LineComponent("cf-17", -1),
                            new LineComponent("cf-18", -1))),
            cashFlowCheck("CF_NET_INCREASE", "现金净增加额=三类净额之和", "cf-20",
                    ReportFormulaDefinition.CheckColumn.COMPARATIVE,
                    List.of(new LineComponent("cf-7", 1), new LineComponent("cf-13", 1),
                            new LineComponent("cf-19", 1))),
            cashFlowCheck("CF_CLOSING_BALANCE", "期末现金余额=净增加额+期初余额", "cf-22",
                    ReportFormulaDefinition.CheckColumn.COMPARATIVE,
                    List.of(new LineComponent("cf-20", 1), new LineComponent("cf-21", 1))));

    private static FormulaCheck cashFlowCheck(String code, String name, String leftLineKey,
                                              ReportFormulaDefinition.CheckColumn column,
                                              List<LineComponent> rightComponents) {
        return new FormulaCheck(code, name, leftLineKey, null, column, rightComponents);
    }

    private final FormulaParser parser;

    public StandardFormulaConverter() {
        this(new FormulaParser());
    }

    public StandardFormulaConverter(FormulaParser parser) {
        this.parser = parser;
    }

    public List<ReportFormulaDefinition> convertAll(AccountingStandard.Package standard) {
        return standard.formulas().stream()
                .map(formula -> convert(standard, formula))
                .toList();
    }

    public ReportFormulaDefinition convert(AccountingStandard.Package standard, AccountingStandard.Formula formula) {
        return switch (standard.code()) {
            case "SME" -> convertSmeStatutory(formula);
            case "CAS" -> convertCasLegacy(formula.definition(), formula.code());
            default -> throw new IllegalArgumentException(
                    "No canonical formula conversion for standard " + standard.key());
        };
    }

    /**
     * Canonical schema-1 JSON for a standard formula.  SME snapshots additionally
     * keep the legacy category arrays (debitCategories/creditCategories and
     * revenueCategories/expenseCategories) at the root so the dynamic
     * category-based report endpoints behave exactly as before.
     */
    public String canonicalJson(AccountingStandard.Package standard, AccountingStandard.Formula formula) {
        ReportFormulaDefinition definition = convert(standard, formula);
        if (!"SME".equals(standard.code())) {
            return parser.write(definition);
        }
        ObjectNode node = (ObjectNode) parser.readTree(parser.write(definition));
        JsonNode legacy = formula.definition();
        for (String field : LEGACY_CATEGORY_FIELDS) {
            JsonNode categories = legacy.path(field);
            if (categories.isArray()) {
                node.set(field, categories);
            }
        }
        return node.toString();
    }

    /** Converts a legacy CAS snapshot definition (category arrays) to ACCOUNT_DETAIL. */
    public ReportFormulaDefinition convertCasLegacy(JsonNode definition, String formulaCode) {
        String reportType = formulaCode;
        String templateCode = CAS_TEMPLATE_PREFIX;
        boolean balanceSheet = ReportFormulaDefinition.REPORT_BALANCE_SHEET.equals(reportType);
        boolean income = ReportFormulaDefinition.REPORT_INCOME_STATEMENT.equals(reportType);
        if (!balanceSheet && !income) {
            throw new IllegalArgumentException("Unsupported CAS formula code: " + formulaCode);
        }
        if (definition == null || !definition.isObject()) {
            throw new IllegalArgumentException("The CAS formula definition must be a JSON object");
        }
        List<DetailRule> rules;
        if (balanceSheet) {
            rules = List.of(
                    rule("DEBIT_CATEGORIES", ReportFormulaDefinition.SIDE_DEBIT,
                            categories(definition, "debitCategories")),
                    rule("CREDIT_CATEGORIES", ReportFormulaDefinition.SIDE_CREDIT,
                            categories(definition, "creditCategories")));
        } else {
            // Revenue is reported credit-minus-debit, expenses debit-minus-credit.
            rules = List.of(
                    rule("REVENUE_CATEGORIES", ReportFormulaDefinition.SIDE_CREDIT,
                            categories(definition, "revenueCategories")),
                    rule("EXPENSE_CATEGORIES", ReportFormulaDefinition.SIDE_DEBIT,
                            categories(definition, "expenseCategories")));
        }
        ColumnPolicy policy = new ColumnPolicy(
                balanceSheet ? AmountBasis.CLOSING : AmountBasis.ACTIVITY, AmountBasis.NONE);
        return new ReportFormulaDefinition(
                ReportFormulaDefinition.CURRENT_SCHEMA_VERSION,
                ReportFormulaDefinition.KIND_ACCOUNT_DETAIL,
                reportType, templateCode, policy, List.of(), rules, List.of());
    }

    private ReportFormulaDefinition convertSmeStatutory(AccountingStandard.Formula formula) {
        JsonNode definition = formula.definition();
        JsonNode statutory = definition == null ? null : definition.path("statutory");
        if (statutory == null || !statutory.isObject()) {
            throw new IllegalArgumentException(
                    "The SME formula " + formula.code() + " has no statutory definition");
        }
        String template = statutory.path("template").asText("");
        if (!SME_TEMPLATE_PREFIX.equals(template)) {
            throw new IllegalArgumentException("Unsupported SME statutory template: " + template);
        }
        String reportType = formula.code();
        boolean balanceSheet = ReportFormulaDefinition.REPORT_BALANCE_SHEET.equals(reportType);
        boolean income = ReportFormulaDefinition.REPORT_INCOME_STATEMENT.equals(reportType);
        boolean cashFlow = ReportFormulaDefinition.REPORT_CASH_FLOW.equals(reportType);
        if (!balanceSheet && !income && !cashFlow) {
            throw new IllegalArgumentException("Unsupported SME formula code: " + formula.code());
        }
        ColumnPolicy policy = switch (statutory.path("periodMode").asText("")) {
            case "CLOSING_AND_OPENING" -> new ColumnPolicy(AmountBasis.CLOSING, AmountBasis.OPENING);
            case "YEAR_TO_DATE_AND_MONTH" -> new ColumnPolicy(AmountBasis.ACTIVITY, AmountBasis.ACTIVITY);
            case String mode -> throw new IllegalArgumentException(
                    "Unsupported SME period mode: " + mode);
        };
        List<FormulaGroup> groups = new ArrayList<>();
        for (JsonNode group : statutory.path("groups")) {
            List<FormulaLine> lines = new ArrayList<>();
            for (JsonNode line : group.path("lines")) {
                lines.add(new FormulaLine(
                        requiredText(line, "key"),
                        line.path("lineNo").asInt(),
                        line.path("indent").asInt(),
                        line.path("rowType").asText("DETAIL"),
                        requiredText(line, "name"),
                        expression(line.path("operation"))));
            }
            groups.add(new FormulaGroup(requiredText(group, "key"), requiredText(group, "title"),
                    List.copyOf(lines)));
        }
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("The SME statutory template has no line definitions");
        }
        return new ReportFormulaDefinition(
                ReportFormulaDefinition.CURRENT_SCHEMA_VERSION,
                ReportFormulaDefinition.KIND_FIXED_LINES,
                reportType, template, policy, List.copyOf(groups), List.of(),
                balanceSheet ? BALANCE_CHECKS : cashFlow ? CASH_FLOW_CHECKS : List.of());
    }

    private ReportFormulaDefinition.LineExpression expression(JsonNode operation) {
        String kind = requiredText(operation, "kind");
        return switch (kind) {
            case "ACCOUNT_BALANCE", "ACCOUNT_ACTIVITY" -> {
                List<AccountReference> accounts = new ArrayList<>();
                for (JsonNode account : operation.path("accounts")) {
                    accounts.add(new AccountReference(
                            ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY,
                            requiredText(account, "key")));
                }
                String basis = operation.path("basis").asText("");
                yield new AccountAmountExpression(kind,
                        operation.path("side").asText(ReportFormulaDefinition.SIDE_DEBIT),
                        List.copyOf(accounts),
                        basis.isBlank() ? null
                                : ReportFormulaDefinition.AmountBasis.valueOf(basis));
            }
            case "LINEAR_COMBINATION" -> {
                List<LineComponent> components = new ArrayList<>();
                for (JsonNode component : operation.path("components")) {
                    components.add(new LineComponent(
                            requiredText(component, "key"), component.path("factor").asInt(1)));
                }
                yield new LinearCombinationExpression(List.copyOf(components));
            }
            case "CASH_FLOW_ITEM_AMOUNT" -> {
                String direction = requiredText(operation, "direction");
                List<String> itemCodes = new ArrayList<>();
                for (JsonNode itemCode : operation.path("itemCodes")) {
                    itemCodes.add(itemCode.asText());
                }
                if (itemCodes.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A statutory cash flow operation needs itemCodes");
                }
                List<AccountReference> cashAccounts = new ArrayList<>();
                for (JsonNode account : operation.path("cashAccounts")) {
                    cashAccounts.add(new AccountReference(
                            ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY,
                            requiredText(account, "key")));
                }
                if (cashAccounts.isEmpty()) {
                    throw new IllegalArgumentException(
                            "A statutory cash flow operation needs cashAccounts");
                }
                yield new ReportFormulaDefinition.CashFlowItemAmountExpression(
                        ReportFormulaDefinition.CashFlowDirection.valueOf(direction),
                        List.copyOf(itemCodes), List.copyOf(cashAccounts));
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported statutory formula operation: " + kind);
        };
    }

    private DetailRule rule(String key, String side, List<String> categories) {
        return new DetailRule(key, side, categories, List.of());
    }

    private List<String> categories(JsonNode definition, String field) {
        JsonNode node = definition.path(field);
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException("The CAS formula definition must contain " + field);
        }
        List<String> result = new ArrayList<>();
        node.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("A statutory definition is missing " + field);
        }
        return value;
    }
}
