package com.example.accounting.ledger.formula;

import com.example.accounting.ledger.AccountCategory;
import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaCheck;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Startup validation of canonical definitions produced from standard packages.
 * Checks schema version, kind/reportType combination, standard account key
 * existence, line key uniqueness, component reference existence and the
 * operation whitelist.  The full save/preview/publish validator used at edit
 * time additionally checks ledger-specific ownership and overlaps.
 */
@Component
public class StandardFormulaValidator {

    private static final Set<String> KINDS = Set.of(
            ReportFormulaDefinition.KIND_FIXED_LINES, ReportFormulaDefinition.KIND_ACCOUNT_DETAIL);
    private static final Set<String> REPORT_TYPES = Set.of(
            ReportFormulaDefinition.REPORT_BALANCE_SHEET, ReportFormulaDefinition.REPORT_INCOME_STATEMENT,
            ReportFormulaDefinition.REPORT_CASH_FLOW);
    private static final Set<String> OPERATIONS = Set.of(
            ReportFormulaDefinition.OP_ACCOUNT_BALANCE, ReportFormulaDefinition.OP_ACCOUNT_ACTIVITY);
    private static final Set<String> SIDES = Set.of(
            ReportFormulaDefinition.SIDE_DEBIT, ReportFormulaDefinition.SIDE_CREDIT);
    private static final Set<ReportFormulaDefinition.CashFlowDirection> DIRECTIONS = Set.of(
            ReportFormulaDefinition.CashFlowDirection.values());

    public void validateAll(AccountingStandard.Package standard) {
        for (ReportFormulaDefinition definition : new StandardFormulaConverter().convertAll(standard)) {
            validate(standard, definition);
        }
    }

    public void validate(AccountingStandard.Package standard, ReportFormulaDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException("The canonical formula definition is missing");
        }
        if (definition.schemaVersion() != ReportFormulaDefinition.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported report formula schema version "
                    + definition.schemaVersion());
        }
        if (!KINDS.contains(definition.kind())) {
            throw new IllegalArgumentException("Invalid report formula kind: " + definition.kind());
        }
        if (!REPORT_TYPES.contains(definition.reportType())) {
            throw new IllegalArgumentException("Invalid report formula type: " + definition.reportType());
        }
        if (definition.templateCode() == null || definition.templateCode().isBlank()) {
            throw new IllegalArgumentException("The report formula template code is missing");
        }
        validateKindShape(definition);
        validateColumnPolicy(definition);
        Set<String> lineKeys = lineKeys(definition);
        validateLines(definition, standard, lineKeys);
        validateRules(definition, standard);
        validateChecks(definition, lineKeys);
    }

    private void validateKindShape(ReportFormulaDefinition definition) {
        boolean fixedLines = ReportFormulaDefinition.KIND_FIXED_LINES.equals(definition.kind());
        if (fixedLines && definition.groups().isEmpty()) {
            throw new IllegalArgumentException("A FIXED_LINES formula must contain line groups");
        }
        if (!fixedLines && !definition.groups().isEmpty()) {
            throw new IllegalArgumentException("An ACCOUNT_DETAIL formula must not contain line groups");
        }
        if (fixedLines && !definition.rules().isEmpty()) {
            throw new IllegalArgumentException("A FIXED_LINES formula must not contain detail rules");
        }
        if (!fixedLines && definition.rules().isEmpty()) {
            throw new IllegalArgumentException("An ACCOUNT_DETAIL formula must contain detail rules");
        }
        if (ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())
                && !ReportFormulaDefinition.KIND_FIXED_LINES.equals(definition.kind())) {
            throw new IllegalArgumentException("A CASH_FLOW formula must use the FIXED_LINES kind");
        }
        if (ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())) {
            int lines = definition.groups().stream()
                    .mapToInt(group -> group.lines().size()).sum();
            if (lines != ReportFormulaDefinition.CASH_FLOW_LINE_COUNT) {
                throw new IllegalArgumentException("A CASH_FLOW formula must keep exactly "
                        + ReportFormulaDefinition.CASH_FLOW_LINE_COUNT + " lines, found " + lines);
            }
        }
    }

    private void validateColumnPolicy(ReportFormulaDefinition definition) {
        ReportFormulaDefinition.ColumnPolicy policy = definition.columnPolicy();
        if (policy == null || policy.primary() == null || policy.primary() == AmountBasis.NONE) {
            throw new IllegalArgumentException("The report formula column policy has no primary column");
        }
        if (policy.comparative() == null) {
            throw new IllegalArgumentException("The report formula column policy has no comparative column");
        }
    }

    private void validateLines(ReportFormulaDefinition definition, AccountingStandard.Package standard,
                               Set<String> lineKeys) {
        Set<String> keys = new HashSet<>();
        Set<String> duplicates = new HashSet<>();
        for (ReportFormulaDefinition.FormulaGroup group : definition.groups()) {
            if (group.key() == null || group.key().isBlank()) {
                throw new IllegalArgumentException("A report formula group has no key");
            }
            for (FormulaLine line : group.lines()) {
                if (line.key() == null || line.key().isBlank()) {
                    throw new IllegalArgumentException("A report formula line has no key");
                }
                if (!keys.add(line.key())) {
                    duplicates.add(line.key());
                }
                if (line.name() == null || line.name().isBlank()) {
                    throw new IllegalArgumentException("Report formula line " + line.key() + " has no name");
                }
                if (line.expression() == null) {
                    throw new IllegalArgumentException("Report formula line " + line.key() + " has no expression");
                }
                validateExpression(line, definition.reportType(), standard, lineKeys);
            }
        }
        if (!duplicates.isEmpty()) {
            throw new IllegalArgumentException("Duplicate report formula line keys: " + duplicates);
        }
    }

    private void validateExpression(
            FormulaLine line, String reportType, AccountingStandard.Package standard,
            Set<String> lineKeys) {
        if (line.expression() instanceof AccountAmountExpression accountAmount) {
            if (!OPERATIONS.contains(accountAmount.operation())) {
                throw new IllegalArgumentException("Unsupported account amount operation "
                        + accountAmount.operation() + " on line " + line.key());
            }
            if (!SIDES.contains(accountAmount.side())) {
                throw new IllegalArgumentException("Unsupported account amount side "
                        + accountAmount.side() + " on line " + line.key());
            }
            if (accountAmount.basis() != null
                    && !ReportFormulaDefinition.OP_ACCOUNT_BALANCE.equals(accountAmount.operation())) {
                throw new IllegalArgumentException("Line " + line.key()
                        + " may use a basis only with ACCOUNT_BALANCE");
            }
            if (accountAmount.basis() != null && accountAmount.basis() != AmountBasis.OPENING
                    && accountAmount.basis() != AmountBasis.CLOSING) {
                throw new IllegalArgumentException("Line " + line.key()
                        + " has an invalid basis " + accountAmount.basis());
            }
            for (AccountReference reference : accountAmount.accounts()) {
                validateStandardReference(line.key(), reference, standard);
            }
        } else if (line.expression() instanceof LinearCombinationExpression combination) {
            for (LineComponent component : combination.components()) {
                if (component.factor() != 1 && component.factor() != -1) {
                    throw new IllegalArgumentException("Line " + line.key() + " has an invalid factor "
                            + component.factor());
                }
                if (!lineKeys.contains(component.lineKey())) {
                    throw new IllegalArgumentException("Line " + line.key() + " references unknown line "
                            + component.lineKey());
                }
            }
        } else if (line.expression() instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow) {
            if (!ReportFormulaDefinition.REPORT_CASH_FLOW.equals(reportType)) {
                throw new IllegalArgumentException(
                        "CASH_FLOW_ITEM_AMOUNT is only allowed in CASH_FLOW formulas");
            }
            if (cashFlow.direction() == null || !DIRECTIONS.contains(cashFlow.direction())) {
                throw new IllegalArgumentException("Line " + line.key()
                        + " has an invalid cash flow direction " + cashFlow.direction());
            }
            if (cashFlow.itemCodes() == null || cashFlow.itemCodes().isEmpty()) {
                throw new IllegalArgumentException("Line " + line.key()
                        + " must reference at least one cash flow item code");
            }
            if (cashFlow.cashAccounts() == null || cashFlow.cashAccounts().isEmpty()) {
                throw new IllegalArgumentException("Line " + line.key()
                        + " must declare at least one cash account reference");
            }
            for (AccountReference reference : cashFlow.cashAccounts()) {
                validateStandardReference(line.key(), reference, standard);
            }
        }
    }

    private void validateStandardReference(
            String owner, AccountReference reference, AccountingStandard.Package standard) {
        if (reference == null || reference.type() == null || reference.value() == null
                || reference.value().isBlank()) {
            throw new IllegalArgumentException(owner + " has an invalid account reference");
        }
        if (!ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY.equals(reference.type())) {
            throw new IllegalArgumentException("Standard templates must not reference concrete accounts ("
                    + owner + ")");
        }
        boolean exists = standard.standardAccountKeys().stream()
                .anyMatch(key -> key.key().equals(reference.value()));
        if (!exists) {
            throw new IllegalArgumentException("Unknown standard account key "
                    + reference.value() + " on " + owner);
        }
    }

    private void validateRules(ReportFormulaDefinition definition, AccountingStandard.Package standard) {
        Set<String> keys = new HashSet<>();
        for (DetailRule rule : definition.rules()) {
            if (rule.key() == null || rule.key().isBlank() || !keys.add(rule.key())) {
                throw new IllegalArgumentException("Detail rules must have unique non-blank keys");
            }
            if (!SIDES.contains(rule.side())) {
                throw new IllegalArgumentException("Detail rule " + rule.key() + " has invalid side "
                        + rule.side());
            }
            if (rule.categories().isEmpty() && rule.accounts().isEmpty()) {
                throw new IllegalArgumentException("Detail rule " + rule.key() + " matches nothing");
            }
            for (String category : rule.categories()) {
                if (!AccountCategory.isValid(category)) {
                    throw new IllegalArgumentException("Detail rule " + rule.key()
                            + " has invalid category " + category);
                }
            }
            for (AccountReference reference : rule.accounts()) {
                validateStandardReference(rule.key(), reference, standard);
            }
        }
    }

    private void validateChecks(ReportFormulaDefinition definition, Set<String> lineKeys) {
        for (FormulaCheck check : definition.checks()) {
            if (check.code() == null || check.code().isBlank()
                    || check.name() == null || check.name().isBlank()) {
                throw new IllegalArgumentException("A formula check has no code or name");
            }
            if (!lineKeys.contains(check.leftLineKey())) {
                throw new IllegalArgumentException("Check " + check.code()
                        + " references an unknown line");
            }
            if (check.hasRightComponents()) {
                for (LineComponent component : check.rightComponents()) {
                    if (component.factor() != 1 && component.factor() != -1) {
                        throw new IllegalArgumentException("Check " + check.code()
                                + " has an invalid factor " + component.factor());
                    }
                    if (!lineKeys.contains(component.lineKey())) {
                        throw new IllegalArgumentException("Check " + check.code()
                                + " references an unknown line " + component.lineKey());
                    }
                }
            } else if (!lineKeys.contains(check.rightLineKey())) {
                throw new IllegalArgumentException("Check " + check.code()
                        + " references an unknown line");
            }
        }
    }

    private Set<String> lineKeys(ReportFormulaDefinition definition) {
        Set<String> keys = new HashSet<>();
        definition.groups().forEach(group -> group.lines().forEach(line -> keys.add(line.key())));
        return keys;
    }
}
