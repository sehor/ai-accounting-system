package com.example.accounting.ledger.formula;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.List;

/**
 * Canonical report formula definition (schema version 1).  This is the single
 * internal contract that standard packages, snapshots, drafts and published
 * revisions all serialize to.  {@code kind} selects between the fixed-line SME
 * statutory form ({@code FIXED_LINES}) and the category/account detail form
 * ({@code ACCOUNT_DETAIL}) used by CAS.
 */
public record ReportFormulaDefinition(
        int schemaVersion,
        String kind,
        String reportType,
        String templateCode,
        ColumnPolicy columnPolicy,
        List<FormulaGroup> groups,
        List<DetailRule> rules,
        List<FormulaCheck> checks) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public static final String KIND_FIXED_LINES = "FIXED_LINES";
    public static final String KIND_ACCOUNT_DETAIL = "ACCOUNT_DETAIL";

    public static final String REPORT_BALANCE_SHEET = "BALANCE_SHEET";
    public static final String REPORT_INCOME_STATEMENT = "INCOME_STATEMENT";

    public static final String REF_STANDARD_ACCOUNT_KEY = "STANDARD_ACCOUNT_KEY";
    public static final String REF_ACCOUNT_ID = "ACCOUNT_ID";

    public static final String OP_ACCOUNT_BALANCE = "ACCOUNT_BALANCE";
    public static final String OP_ACCOUNT_ACTIVITY = "ACCOUNT_ACTIVITY";

    public static final String SIDE_DEBIT = "DEBIT";
    public static final String SIDE_CREDIT = "CREDIT";

    public ReportFormulaDefinition {
        groups = groups == null ? List.of() : List.copyOf(groups);
        rules = rules == null ? List.of() : List.copyOf(rules);
        checks = checks == null ? List.of() : List.copyOf(checks);
    }

    public record FormulaGroup(String key, String title, List<FormulaLine> lines) {
        public FormulaGroup {
            lines = lines == null ? List.of() : List.copyOf(lines);
        }
    }

    public record FormulaLine(
            String key, int lineNo, int indent, String rowType, String name,
            LineExpression expression) {
    }

    /** Discriminated expression: {@code ACCOUNT_AMOUNT} or {@code LINEAR_COMBINATION}. */
    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes({
            @JsonSubTypes.Type(value = AccountAmountExpression.class, name = "ACCOUNT_AMOUNT"),
            @JsonSubTypes.Type(value = LinearCombinationExpression.class, name = "LINEAR_COMBINATION")
    })
    public sealed interface LineExpression permits AccountAmountExpression, LinearCombinationExpression {
    }

    public record AccountAmountExpression(
            String operation, String side, List<AccountReference> accounts) implements LineExpression {
        public AccountAmountExpression {
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    public record LinearCombinationExpression(List<LineComponent> components) implements LineExpression {
        public LinearCombinationExpression {
            components = components == null ? List.of() : List.copyOf(components);
        }
    }

    /** Factor is restricted to +1 / -1. */
    public record LineComponent(String lineKey, int factor) {
    }

    /** References either a standard package key or a concrete ledger account id. */
    public record AccountReference(String type, String value) {
    }

    public record DetailRule(
            String key, String side, List<String> categories,
            List<AccountReference> accounts) {
        public DetailRule {
            categories = categories == null ? List.of() : List.copyOf(categories);
            accounts = accounts == null ? List.of() : List.copyOf(accounts);
        }
    }

    public record FormulaCheck(String code, String name, String leftLineKey, String rightLineKey) {
    }

    /** Amount basis of each column, resolved from the definition, never by template name. */
    public record ColumnPolicy(AmountBasis primary, AmountBasis comparative) {
    }

    public enum AmountBasis {
        /** Closing balances of the selected period (or request range end). */
        CLOSING,
        /** Period activity (occurrences) of the selected period or range. */
        ACTIVITY,
        /** Opening balances of the year's first period. */
        OPENING,
        /** Column not present in the response. */
        NONE
    }
}
