package com.example.accounting.reporting.formula;

import com.example.accounting.ledger.AccountCategory;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaCheck;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Blocking validator reused by save, preview, publish, migration and rollback.
 * The validation order is fixed: shape, kind/reportType, operation whitelist,
 * locked structure, names and limits, line keys and backward references,
 * account ownership, expanded-reference overlap, and CAS side conflicts.
 */
@Component
public class ReportFormulaValidator {

    private static final int MAX_NAME_LENGTH = 200;
    private static final int MAX_REFERENCES_PER_LINE = 100;
    private static final int MAX_AST_NODES = 2000;
    private static final Set<String> OPERATIONS = Set.of(
            ReportFormulaDefinition.OP_ACCOUNT_BALANCE, ReportFormulaDefinition.OP_ACCOUNT_ACTIVITY);
    private static final Set<String> SIDES = Set.of(
            ReportFormulaDefinition.SIDE_DEBIT, ReportFormulaDefinition.SIDE_CREDIT);
    private static final Set<ReportFormulaDefinition.CashFlowDirection> DIRECTIONS = Set.of(
            ReportFormulaDefinition.CashFlowDirection.values());
    private static final Set<String> REFERENCE_TYPES = Set.of(
            ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY, ReportFormulaDefinition.REF_ACCOUNT_ID);

    private final ReportingRepository reports;
    private final FormulaAccountResolver resolver;

    public ReportFormulaValidator(ReportingRepository reports, FormulaAccountResolver resolver) {
        this.reports = reports;
        this.resolver = resolver;
    }

    public record FormulaIssue(String code, String path, String message) {
    }

    /** Validates without a locked-structure base (migration, rollback of old versions). */
    public List<FormulaIssue> validate(ReportFormulaDefinition definition, UUID ledgerId) {
        return validate(definition, null, ledgerId);
    }

    /**
     * Validates the definition against an optional base definition that carries
     * the locked template structure (current published version for save/preview).
     */
    public List<FormulaIssue> validate(
            ReportFormulaDefinition definition, ReportFormulaDefinition base, UUID ledgerId) {
        List<FormulaIssue> issues = new ArrayList<>();
        if (definition == null) {
            issues.add(new FormulaIssue("FORMULA_MISSING", "", "Formula definition is missing"));
            return issues;
        }
        validateShape(definition, issues);
        validateKindAndReportType(definition, issues);
        validateOperationWhitelist(definition, issues);
        validateLockedStructure(definition, base, issues);
        validateNamesAndLimits(definition, issues);
        validateLineKeysAndBackwardReferences(definition, issues);
        validateCashFlowItemCodes(definition, ledgerId, issues);
        validateAccountOwnership(definition, ledgerId, issues);
        expandReferencesAndRejectOverlap(definition, ledgerId, issues);
        validateCasSideConflicts(definition, ledgerId, issues);
        return issues;
    }

    public void requireValid(ReportFormulaDefinition definition, UUID ledgerId) {
        requireValid(definition, null, ledgerId);
    }

    public void requireValid(ReportFormulaDefinition definition, ReportFormulaDefinition base, UUID ledgerId) {
        List<FormulaIssue> issues = validate(definition, base, ledgerId);
        if (!issues.isEmpty()) {
            throw new com.example.accounting.shared.web.ApiProblemException(
                    422, "REPORT_FORMULA_INVALID", "Report formula is invalid",
                    issues.toString(), false);
        }
    }

    private void validateShape(ReportFormulaDefinition definition, List<FormulaIssue> issues) {
        if (definition.schemaVersion() != ReportFormulaDefinition.CURRENT_SCHEMA_VERSION) {
            issues.add(new FormulaIssue("SCHEMA_VERSION", "schemaVersion",
                    "Unsupported schema version " + definition.schemaVersion()));
        }
        if (definition.templateCode() == null || definition.templateCode().isBlank()) {
            issues.add(new FormulaIssue("TEMPLATE_CODE", "templateCode", "Template code is required"));
        }
        if (definition.columnPolicy() == null || definition.columnPolicy().primary() == null
                || definition.columnPolicy().primary() == AmountBasis.NONE) {
            issues.add(new FormulaIssue("COLUMN_POLICY", "columnPolicy", "A primary column is required"));
        }
    }

    private void validateKindAndReportType(ReportFormulaDefinition definition, List<FormulaIssue> issues) {
        boolean fixedLines = ReportFormulaDefinition.KIND_FIXED_LINES.equals(definition.kind());
        boolean accountDetail = ReportFormulaDefinition.KIND_ACCOUNT_DETAIL.equals(definition.kind());
        if (!fixedLines && !accountDetail) {
            issues.add(new FormulaIssue("KIND_INVALID", "kind", "Unknown kind " + definition.kind()));
            return;
        }
        if (!ReportFormulaDefinition.REPORT_BALANCE_SHEET.equals(definition.reportType())
                && !ReportFormulaDefinition.REPORT_INCOME_STATEMENT.equals(definition.reportType())
                && !ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())) {
            issues.add(new FormulaIssue("REPORT_TYPE_INVALID", "reportType",
                    "Unknown report type " + definition.reportType()));
        }
        if (fixedLines && definition.groups().isEmpty()) {
            issues.add(new FormulaIssue("GROUPS_REQUIRED", "groups",
                    "A FIXED_LINES formula requires line groups"));
        }
        if (fixedLines && !definition.rules().isEmpty()) {
            issues.add(new FormulaIssue("RULES_FORBIDDEN", "rules",
                    "A FIXED_LINES formula must not contain detail rules"));
        }
        if (accountDetail && definition.rules().isEmpty()) {
            issues.add(new FormulaIssue("RULES_REQUIRED", "rules",
                    "An ACCOUNT_DETAIL formula requires detail rules"));
        }
        if (accountDetail && !definition.groups().isEmpty()) {
            issues.add(new FormulaIssue("GROUPS_FORBIDDEN", "groups",
                    "An ACCOUNT_DETAIL formula must not contain line groups"));
        }
        if (ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())
                && !fixedLines) {
            issues.add(new FormulaIssue("KIND_INVALID", "kind",
                    "A CASH_FLOW formula must use the FIXED_LINES kind"));
        }
        if (ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())) {
            int lines = definition.groups().stream()
                    .mapToInt(group -> group.lines().size()).sum();
            if (lines != ReportFormulaDefinition.CASH_FLOW_LINE_COUNT) {
                issues.add(new FormulaIssue("STRUCTURE_LOCKED", "groups",
                        "A CASH_FLOW formula must keep exactly "
                                + ReportFormulaDefinition.CASH_FLOW_LINE_COUNT + " lines, found " + lines));
            }
        }
    }

    private void validateOperationWhitelist(ReportFormulaDefinition definition, List<FormulaIssue> issues) {
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof AccountAmountExpression accountAmount) {
                    if (!OPERATIONS.contains(accountAmount.operation())) {
                        issues.add(new FormulaIssue("OPERATION_INVALID", path(line.key(), "operation"),
                                "Unsupported operation " + accountAmount.operation()));
                    }
                    if (!SIDES.contains(accountAmount.side())) {
                        issues.add(new FormulaIssue("SIDE_INVALID", path(line.key(), "side"),
                                "Unsupported side " + accountAmount.side()));
                    }
                    if (accountAmount.basis() != null
                            && !ReportFormulaDefinition.OP_ACCOUNT_BALANCE.equals(accountAmount.operation())) {
                        issues.add(new FormulaIssue("BASIS_INVALID", path(line.key(), "basis"),
                                "A basis is only allowed on ACCOUNT_BALANCE expressions"));
                    }
                    if (accountAmount.basis() != null && accountAmount.basis() != AmountBasis.OPENING
                            && accountAmount.basis() != AmountBasis.CLOSING) {
                        issues.add(new FormulaIssue("BASIS_INVALID", path(line.key(), "basis"),
                                "A line-level basis must be OPENING or CLOSING"));
                    }
                } else if (line.expression() instanceof LinearCombinationExpression combination) {
                    for (LineComponent component : combination.components()) {
                        if (component.factor() != 1 && component.factor() != -1) {
                            issues.add(new FormulaIssue("FACTOR_INVALID",
                                    path(line.key(), "components." + component.lineKey()),
                                    "Factor must be +1 or -1"));
                        }
                    }
                } else if (line.expression()
                        instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow) {
                    if (!ReportFormulaDefinition.REPORT_CASH_FLOW.equals(definition.reportType())) {
                        issues.add(new FormulaIssue("EXPRESSION_TYPE_INVALID", path(line.key(), "type"),
                                "CASH_FLOW_ITEM_AMOUNT is only allowed in CASH_FLOW formulas"));
                    }
                    if (cashFlow.direction() == null || !DIRECTIONS.contains(cashFlow.direction())) {
                        issues.add(new FormulaIssue("DIRECTION_INVALID", path(line.key(), "direction"),
                                "Unsupported cash flow direction " + cashFlow.direction()));
                    }
                    if (cashFlow.itemCodes() == null || cashFlow.itemCodes().isEmpty()) {
                        issues.add(new FormulaIssue("ITEM_CODES_REQUIRED", path(line.key(), "itemCodes"),
                                "A cash flow expression must reference at least one item code"));
                    }
                    if (cashFlow.cashAccounts() == null || cashFlow.cashAccounts().isEmpty()) {
                        issues.add(new FormulaIssue("CASH_ACCOUNTS_REQUIRED", path(line.key(), "cashAccounts"),
                                "A cash flow expression must declare at least one cash account"));
                    }
                }
            }
        }
        Set<String> ruleKeys = new HashSet<>();
        for (DetailRule rule : definition.rules()) {
            if (rule.key() == null || rule.key().isBlank()) {
                issues.add(new FormulaIssue("RULE_KEY_INVALID", "rules", "Every detail rule needs a key"));
            } else if (!ruleKeys.add(rule.key())) {
                issues.add(new FormulaIssue("RULE_KEY_DUPLICATE", path(rule.key(), "key"),
                        "Duplicate detail rule key " + rule.key()));
            }
            if (!SIDES.contains(rule.side())) {
                issues.add(new FormulaIssue("SIDE_INVALID", path(rule.key(), "side"),
                        "Unsupported side " + rule.side()));
            }
            if (rule.categories().isEmpty() && rule.accounts().isEmpty()) {
                issues.add(new FormulaIssue("RULE_EMPTY", path(rule.key(), "matches"),
                        "A detail rule needs at least one category or account"));
            }
            for (String category : rule.categories()) {
                if (!AccountCategory.isValid(category)) {
                    issues.add(new FormulaIssue("CATEGORY_INVALID", path(rule.key(), "categories"),
                            "Unknown account category " + category));
                }
            }
        }
    }

    private void validateLockedStructure(
            ReportFormulaDefinition definition, ReportFormulaDefinition base, List<FormulaIssue> issues) {
        if (base == null || !ReportFormulaDefinition.KIND_FIXED_LINES.equals(definition.kind())) {
            return;
        }
        List<FormulaGroup> expected = base.groups();
        List<FormulaGroup> actual = definition.groups();
        if (expected.size() != actual.size()) {
            issues.add(new FormulaIssue("STRUCTURE_LOCKED", "groups", "Group structure is locked"));
            return;
        }
        for (int index = 0; index < expected.size(); index++) {
            FormulaGroup expectedGroup = expected.get(index);
            FormulaGroup actualGroup = actual.get(index);
            if (!expectedGroup.key().equals(actualGroup.key())
                    || !expectedGroup.title().equals(actualGroup.title())) {
                issues.add(new FormulaIssue("STRUCTURE_LOCKED", "groups[" + index + "]",
                        "Group keys and titles are locked"));
                continue;
            }
            List<FormulaLine> expectedLines = expectedGroup.lines();
            List<FormulaLine> actualLines = actualGroup.lines();
            if (expectedLines.size() != actualLines.size()) {
                issues.add(new FormulaIssue("STRUCTURE_LOCKED", "groups[" + index + "].lines",
                        "Line count is locked"));
                continue;
            }
            for (int lineIndex = 0; lineIndex < expectedLines.size(); lineIndex++) {
                FormulaLine expectedLine = expectedLines.get(lineIndex);
                FormulaLine actualLine = actualLines.get(lineIndex);
                if (!expectedLine.key().equals(actualLine.key())
                        || expectedLine.lineNo() != actualLine.lineNo()
                        || expectedLine.indent() != actualLine.indent()
                        || !expectedLine.rowType().equals(actualLine.rowType())) {
                    issues.add(new FormulaIssue("STRUCTURE_LOCKED",
                            "groups[" + index + "].lines[" + lineIndex + "]",
                            "Line numbers, keys, indents and row types are locked"));
                }
            }
        }
        if (!base.checks().equals(definition.checks())) {
            issues.add(new FormulaIssue("STRUCTURE_LOCKED", "checks", "Checks are locked"));
        }
        if (!base.columnPolicy().equals(definition.columnPolicy())) {
            issues.add(new FormulaIssue("STRUCTURE_LOCKED", "columnPolicy", "Column policy is locked"));
        }
    }

    /**
     * A cash flow item code may be referenced by at most one detail line of the
     * definition: two lines claiming the same code would double count its cash.
     */
    private void validateCashFlowItemCodes(
            ReportFormulaDefinition definition, UUID ledgerId, List<FormulaIssue> issues) {
        Map<String, String> codeToLine = new HashMap<>();
        Set<String> activeCodes = null;
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (!(line.expression()
                        instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow)) {
                    continue;
                }
                for (String itemCode : cashFlow.itemCodes()) {
                    if (itemCode == null || itemCode.isBlank()) {
                        issues.add(new FormulaIssue("ITEM_CODE_INVALID", path(line.key(), "itemCodes"),
                                "Cash flow item codes must be non-blank"));
                    } else {
                        if (activeCodes == null) {
                            activeCodes = reports.activeCashFlowItemCodes(ledgerId);
                        }
                        if (!activeCodes.contains(itemCode)) {
                            issues.add(new FormulaIssue("ITEM_CODE_NOT_REPORTABLE",
                                    path(line.key(), "itemCodes"),
                                    "Cash flow item " + itemCode
                                            + " is not active in this ledger"));
                        }
                        String owner = codeToLine.putIfAbsent(itemCode, line.key());
                        if (owner != null) {
                            issues.add(new FormulaIssue("ITEM_CODE_DUPLICATE",
                                    path(line.key(), "itemCodes"),
                                    "Cash flow item " + itemCode + " is referenced by both "
                                            + owner + " and " + line.key()));
                        }
                    }
                }
            }
        }
    }

    private void validateNamesAndLimits(ReportFormulaDefinition definition, List<FormulaIssue> issues) {
        int nodes = 1;
        for (FormulaGroup group : definition.groups()) {
            nodes++;
            for (FormulaLine line : group.lines()) {
                nodes++;
                if (line.name() == null || line.name().isBlank() || line.name().length() > MAX_NAME_LENGTH) {
                    issues.add(new FormulaIssue("NAME_INVALID", path(line.key(), "name"),
                            "Line name must contain 1 to " + MAX_NAME_LENGTH + " characters"));
                }
                if (line.expression() instanceof AccountAmountExpression accountAmount) {
                    nodes += 1 + accountAmount.accounts().size();
                    if (accountAmount.accounts().size() > MAX_REFERENCES_PER_LINE) {
                        issues.add(new FormulaIssue("LIMIT_EXCEEDED", path(line.key(), "accounts"),
                                "A line may reference at most " + MAX_REFERENCES_PER_LINE + " accounts"));
                    }
                } else if (line.expression() instanceof LinearCombinationExpression combination) {
                    nodes += 1 + combination.components().size();
                    if (combination.components().size() > MAX_REFERENCES_PER_LINE) {
                        issues.add(new FormulaIssue("LIMIT_EXCEEDED", path(line.key(), "components"),
                                "A line may combine at most " + MAX_REFERENCES_PER_LINE + " lines"));
                    }
                } else if (line.expression()
                        instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow) {
                    nodes += 1 + cashFlow.itemCodes().size() + cashFlow.cashAccounts().size();
                    if (cashFlow.cashAccounts().size() > MAX_REFERENCES_PER_LINE) {
                        issues.add(new FormulaIssue("LIMIT_EXCEEDED", path(line.key(), "cashAccounts"),
                                "A line may declare at most " + MAX_REFERENCES_PER_LINE + " cash accounts"));
                    }
                }
            }
        }
        for (DetailRule rule : definition.rules()) {
            nodes += 1 + rule.categories().size() + rule.accounts().size();
        }
        nodes += definition.checks().size();
        if (nodes > MAX_AST_NODES) {
            issues.add(new FormulaIssue("LIMIT_EXCEEDED", "",
                    "The definition exceeds " + MAX_AST_NODES + " AST nodes"));
        }
    }

    private void validateLineKeysAndBackwardReferences(
            ReportFormulaDefinition definition, List<FormulaIssue> issues) {
        Map<String, Integer> order = new HashMap<>();
        int position = 0;
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.key() == null || line.key().isBlank()) {
                    issues.add(new FormulaIssue("LINE_KEY_INVALID", "groups",
                            "Every line needs a non-blank key"));
                } else if (order.putIfAbsent(line.key(), position) != null) {
                    issues.add(new FormulaIssue("LINE_KEY_DUPLICATE", path(line.key(), "key"),
                            "Duplicate line key " + line.key()));
                }
                position++;
            }
        }
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (!(line.expression() instanceof LinearCombinationExpression combination)) {
                    continue;
                }
                Integer current = order.get(line.key());
                for (LineComponent component : combination.components()) {
                    Integer referenced = order.get(component.lineKey());
                    if (referenced == null) {
                        issues.add(new FormulaIssue("REFERENCE_UNKNOWN", path(line.key(), "components"),
                                "Line " + line.key() + " references unknown line " + component.lineKey()));
                    } else if (current != null && referenced >= current) {
                        issues.add(new FormulaIssue("BACKWARD_REFERENCE_REQUIRED",
                                path(line.key(), "components." + component.lineKey()),
                                "Line " + line.key() + " may only reference earlier lines"));
                    }
                }
            }
        }
    }

    private void validateAccountOwnership(
            ReportFormulaDefinition definition, UUID ledgerId, List<FormulaIssue> issues) {
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof AccountAmountExpression accountAmount) {
                    for (AccountReference reference : accountAmount.accounts()) {
                        validateReferenceOwnership(ledgerId, reference,
                                path(line.key(), "accounts"), issues);
                    }
                } else if (line.expression()
                        instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow) {
                    for (AccountReference reference : cashFlow.cashAccounts()) {
                        validateReferenceOwnership(ledgerId, reference,
                                path(line.key(), "cashAccounts"), issues);
                    }
                }
            }
        }
        for (DetailRule rule : definition.rules()) {
            for (AccountReference reference : rule.accounts()) {
                validateReferenceOwnership(ledgerId, reference,
                        path(rule.key(), "accounts"), issues);
            }
        }
    }

    private void validateReferenceOwnership(UUID ledgerId, AccountReference reference,
                                            String path, List<FormulaIssue> issues) {
        if (reference == null || reference.value() == null || reference.value().isBlank()) {
            issues.add(new FormulaIssue("REFERENCE_INVALID", path, "Account references are required"));
            return;
        }
        if (!REFERENCE_TYPES.contains(reference.type())) {
            issues.add(new FormulaIssue("REFERENCE_INVALID", path,
                    "Unsupported account reference type " + reference.type()));
            return;
        }
        if (ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY.equals(reference.type())) {
            // A standard key may legitimately be absent from a ledger. Evaluation treats it as zero.
            return;
        }
        UUID accountId;
        try {
            accountId = UUID.fromString(reference.value());
        } catch (IllegalArgumentException exception) {
            issues.add(new FormulaIssue("REFERENCE_INVALID", path,
                    "Account id " + reference.value() + " is not a UUID"));
            return;
        }
        if (!reports.accountExists(ledgerId, accountId)) {
            issues.add(new FormulaIssue("ACCOUNT_OUTSIDE_LEDGER", path,
                    "Account " + reference.value() + " does not belong to this ledger"));
        }
    }

    private void expandReferencesAndRejectOverlap(
            ReportFormulaDefinition definition, UUID ledgerId, List<FormulaIssue> issues) {
        for (FormulaGroup group : definition.groups()) {
            for (FormulaLine line : group.lines()) {
                if (line.expression() instanceof AccountAmountExpression accountAmount) {
                    rejectOverlappingReferences(ledgerId, line.key(), accountAmount.accounts(), issues);
                } else if (line.expression()
                        instanceof ReportFormulaDefinition.CashFlowItemAmountExpression cashFlow) {
                    rejectOverlappingReferences(ledgerId, line.key(), cashFlow.cashAccounts(), issues);
                }
            }
        }
        for (DetailRule rule : definition.rules()) {
            rejectOverlappingReferences(ledgerId, rule.key(), rule.accounts(), issues);
        }
    }

    /**
     * A reference resolves to its mapped leaf set (standard key) or its leaf
     * descendants (concrete account).  The expanded sets within one expression
     * must be disjoint: the sum of per-reference leaf counts must equal the
     * size of their union.
     */
    private void rejectOverlappingReferences(UUID ledgerId, String owner,
                                             List<AccountReference> accounts, List<FormulaIssue> issues) {
        int total = 0;
        Set<UUID> union = new HashSet<>();
        for (AccountReference reference : accounts) {
            Set<UUID> expanded = resolver.expandToLeafIds(ledgerId, List.of(reference));
            total += expanded.size();
            union.addAll(expanded);
        }
        if (total > union.size()) {
            issues.add(new FormulaIssue("OVERLAPPING_REFERENCES", path(owner, "accounts"),
                    "Expanded account references of " + owner + " overlap"));
        }
    }

    private void validateCasSideConflicts(
            ReportFormulaDefinition definition, UUID ledgerId, List<FormulaIssue> issues) {
        if (!ReportFormulaDefinition.KIND_ACCOUNT_DETAIL.equals(definition.kind())) {
            return;
        }
        List<DetailRule> rules = definition.rules();
        List<Set<UUID>> matchedLeaves = rules.stream()
                .map(rule -> resolver.expandRuleToLeafIds(ledgerId, rule))
                .toList();
        for (int left = 0; left < rules.size(); left++) {
            for (int right = left + 1; right < rules.size(); right++) {
                DetailRule a = rules.get(left);
                DetailRule b = rules.get(right);
                if (Objects.equals(a.side(), b.side())) {
                    continue;
                }
                Set<UUID> shared = new HashSet<>(matchedLeaves.get(left));
                shared.retainAll(matchedLeaves.get(right));
                if (!shared.isEmpty()) {
                    issues.add(new FormulaIssue("SIDE_CONFLICT", path(a.key(), "matches"),
                            "Rules " + a.key() + " and " + b.key()
                                    + " match the same accounts with conflicting sides"));
                }
            }
        }
    }

    private String path(String owner, String field) {
        return owner == null ? field : owner + "." + field;
    }
}
