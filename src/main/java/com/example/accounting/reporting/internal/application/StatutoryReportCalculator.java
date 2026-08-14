package com.example.accounting.reporting.internal.application;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Evaluates the fixed SME statutory forms selected by the accounting-standard package.
 * Operations are deliberately limited to a small, auditable whitelist rather than an
 * arbitrary expression language.
 */
final class StatutoryReportCalculator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    StatutoryReportResponses.Statement calculate(
            String reportType,
            String periodCode,
            String standardVersion,
            JsonNode formula,
            List<ReportResponses.TrialBalanceLine> primarySource,
            List<ReportResponses.TrialBalanceLine> comparativeSource) {
        JsonNode statutory = formula == null ? null : formula.path("statutory");
        if (statutory == null || !"SME-2011-17".equals(statutory.path("template").asText())) {
            throw new IllegalArgumentException("The SME statutory report template is not installed");
        }

        boolean income = "income-statement".equals(reportType);
        List<GroupDefinition> groups = groupDefinitions(statutory.path("groups"));
        validateTemplate(statutory, groups, income);
        Map<String, ReportResponses.TrialBalanceLine> primary = byCode(primarySource);
        Map<String, ReportResponses.TrialBalanceLine> comparative = byCode(comparativeSource);
        Map<String, BigDecimal[]> calculated = new LinkedHashMap<>();
        List<StatutoryReportResponses.Group> result = new ArrayList<>();

        for (GroupDefinition group : groups) {
            List<StatutoryReportResponses.Line> rows = new ArrayList<>();
            for (Definition definition : group.rows()) {
                BigDecimal primaryAmount = evaluate(definition, primary, calculated, 0);
                BigDecimal comparativeAmount = evaluate(definition, comparative, calculated, 1);
                calculated.put(definition.key(), new BigDecimal[]{primaryAmount, comparativeAmount});
                rows.add(new StatutoryReportResponses.Line(
                        definition.key(), definition.lineNo(), definition.name(), definition.indent(),
                        definition.rowType(), primaryAmount, comparativeAmount));
            }
            result.add(new StatutoryReportResponses.Group(group.key(), group.title(), rows));
        }

        List<StatutoryReportResponses.Check> checks = income ? List.of() : balanceChecks(calculated);
        return new StatutoryReportResponses.Statement(
                reportType, "SME-2011-17", "SME", standardVersion, periodCode,
                income ? "本年累计金额" : "期末余额", income ? "本月金额" : "年初余额", result, checks);
    }

    private void validateTemplate(JsonNode statutory, List<GroupDefinition> groups, boolean income) {
        int expectedLines = statutory.path("lineCount").asInt(0);
        long actualLines = groups.stream().flatMap(group -> group.rows().stream())
                .filter(row -> row.lineNo() > 0).count();
        if (expectedLines > 0 && (expectedLines != actualLines
                || (!income && actualLines != 53) || (income && actualLines != 32))) {
            throw new IllegalArgumentException("The SME statutory template has an invalid line count");
        }

        HashSet<String> allowed = new HashSet<>();
        statutory.path("allowedOperations").forEach(node -> allowed.add(node.asText()));
        groups.stream().flatMap(group -> group.rows().stream())
                .map(row -> row.operation().kind())
                .filter(kind -> !allowed.contains(kind))
                .findFirst()
                .ifPresent(kind -> {
                    throw new IllegalArgumentException("Unsupported operation in SME statutory template: " + kind);
                });
    }

    private List<GroupDefinition> groupDefinitions(JsonNode definitions) {
        if (!definitions.isArray() || definitions.isEmpty()) {
            throw new IllegalArgumentException("The SME statutory report template has no line definitions");
        }
        List<GroupDefinition> groups = new ArrayList<>();
        for (JsonNode group : definitions) {
            List<Definition> lines = new ArrayList<>();
            for (JsonNode line : group.path("lines")) {
                lines.add(new Definition(
                        requiredText(line, "key"),
                        line.path("lineNo").asInt(),
                        requiredText(line, "name"),
                        line.path("indent").asInt(),
                        line.path("rowType").asText("DETAIL"),
                        operation(line.path("operation"))));
            }
            groups.add(new GroupDefinition(requiredText(group, "key"), requiredText(group, "title"), lines));
        }
        return List.copyOf(groups);
    }

    private Operation operation(JsonNode definition) {
        String kind = requiredText(definition, "kind");
        List<AccountReference> accounts = new ArrayList<>();
        for (JsonNode account : definition.path("accounts")) {
            accounts.add(new AccountReference(
                    requiredText(account, "code"),
                    account.hasNonNull("name") ? account.get("name").asText() : null));
        }
        List<Component> components = new ArrayList<>();
        for (JsonNode component : definition.path("components")) {
            components.add(new Component(requiredText(component, "key"), component.path("factor").asInt(1)));
        }
        return new Operation(kind, definition.path("side").asText("DEBIT"),
                List.copyOf(accounts), List.copyOf(components));
    }

    private String requiredText(JsonNode node, String field) {
        String value = node.path(field).asText();
        if (value.isBlank()) {
            throw new IllegalArgumentException("The SME statutory report template is missing " + field);
        }
        return value;
    }

    private BigDecimal evaluate(
            Definition definition,
            Map<String, ReportResponses.TrialBalanceLine> source,
            Map<String, BigDecimal[]> calculated,
            int column) {
        return switch (definition.operation().kind()) {
            case "ACCOUNT_BALANCE" -> accountAmount(definition.operation(), source, false);
            case "ACCOUNT_ACTIVITY" -> accountAmount(definition.operation(), source, true);
            case "LINEAR_COMBINATION" -> definition.operation().components().stream()
                    .map(component -> value(calculated, component.key(), column)
                            .multiply(BigDecimal.valueOf(component.factor())))
                    .reduce(ZERO, BigDecimal::add);
            default -> throw new IllegalArgumentException(
                    "Unsupported statutory formula operation: " + definition.operation().kind());
        };
    }

    private BigDecimal accountAmount(
            Operation operation,
            Map<String, ReportResponses.TrialBalanceLine> source,
            boolean activity) {
        BigDecimal value = ZERO;
        for (AccountReference account : operation.accounts()) {
            ReportResponses.TrialBalanceLine line = source.get(account.code());
            if (line == null || (account.name() != null && !account.name().equals(line.name()))) {
                continue;
            }
            BigDecimal debit = activity ? line.periodDebit() : line.closingDebit();
            BigDecimal credit = activity ? line.periodCredit() : line.closingCredit();
            BigDecimal signed = "CREDIT".equals(operation.side()) ? credit.subtract(debit) : debit.subtract(credit);
            value = value.add(signed);
        }
        return money(value);
    }

    private List<StatutoryReportResponses.Check> balanceChecks(Map<String, BigDecimal[]> values) {
        BigDecimal primaryDifference = value(values, "bs-30", 0).subtract(value(values, "bs-53", 0));
        BigDecimal comparativeDifference = value(values, "bs-30", 1).subtract(value(values, "bs-53", 1));
        return List.of(
                new StatutoryReportResponses.Check(
                        "ASSET_EQUATION", "期末资产总计=负债和所有者权益总计",
                        primaryDifference.compareTo(BigDecimal.ZERO) == 0, primaryDifference),
                new StatutoryReportResponses.Check(
                        "OPENING_EQUATION", "年初资产总计=负债和所有者权益总计",
                        comparativeDifference.compareTo(BigDecimal.ZERO) == 0, comparativeDifference));
    }

    private static BigDecimal value(Map<String, BigDecimal[]> values, String key, int index) {
        BigDecimal[] pair = values.get(key);
        return pair == null ? ZERO : pair[index];
    }

    private static Map<String, ReportResponses.TrialBalanceLine> byCode(
            List<ReportResponses.TrialBalanceLine> lines) {
        Map<String, ReportResponses.TrialBalanceLine> result = new LinkedHashMap<>();
        lines.forEach(line -> result.put(line.code(), line));
        return result;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private record GroupDefinition(String key, String title, List<Definition> rows) { }
    private record Definition(String key, int lineNo, String name, int indent, String rowType, Operation operation) { }
    private record Operation(String kind, String side, List<AccountReference> accounts, List<Component> components) { }
    private record AccountReference(String code, String name) { }
    private record Component(String key, int factor) { }
}
