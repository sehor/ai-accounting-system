package com.example.accounting.reporting.internal.application;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;

/**
 * Calculates the fixed SME statutory forms. The template is deliberately data driven at the
 * boundary (the standard package selects it), while the supported operations remain a small,
 * auditable whitelist rather than an arbitrary expression language.
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
        if (formula == null || !"SME-2011-17".equals(formula.path("statutory").path("template").asText())) {
            throw new IllegalArgumentException("The SME statutory report template is not installed");
        }
        boolean income = "income-statement".equals(reportType);
        List<GroupDefinition> groups = income ? incomeGroups() : balanceGroups();
        validateTemplate(formula.path("statutory"), groups, income);
        Map<String, ReportResponses.TrialBalanceLine> primary = byCode(primarySource);
        Map<String, ReportResponses.TrialBalanceLine> comparative = byCode(comparativeSource);
        List<StatutoryReportResponses.Group> result = new ArrayList<>();
        Map<String, BigDecimal[]> calculated = new LinkedHashMap<>();
        for (GroupDefinition group : groups) {
            List<StatutoryReportResponses.Line> rows = new ArrayList<>();
            for (Definition definition : group.rows()) {
                BigDecimal primaryAmount = evaluate(definition, primary, calculated, income, true);
                BigDecimal comparativeAmount = evaluate(definition, comparative, calculated, income, false);
                calculated.put(definition.key(), new BigDecimal[]{primaryAmount, comparativeAmount});
                rows.add(new StatutoryReportResponses.Line(definition.key(), definition.lineNo(), definition.name(),
                        definition.indent(), definition.rowType(), primaryAmount, comparativeAmount));
            }
            result.add(new StatutoryReportResponses.Group(group.key(), group.title(), rows));
        }
        List<StatutoryReportResponses.Check> checks = income
                ? List.of()
                : balanceChecks(calculated);
        return new StatutoryReportResponses.Statement(reportType, "SME-2011-17", "SME", standardVersion,
                periodCode, income ? "本年累计金额" : "期末余额", income ? "本月金额" : "年初余额", result, checks);
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
        if (!allowed.isEmpty()) {
            groups.stream().flatMap(group -> group.rows().stream())
                    .map(row -> row.operation().kind())
                    .filter(kind -> !allowed.contains(kind))
                    .findFirst()
                    .ifPresent(kind -> { throw new IllegalArgumentException(
                            "Unsupported operation in SME statutory template: " + kind); });
        }
    }

    private List<StatutoryReportResponses.Check> balanceChecks(Map<String, BigDecimal[]> values) {
        BigDecimal primaryDifference = value(values, "bs-30", 0).subtract(value(values, "bs-53", 0));
        BigDecimal comparativeDifference = value(values, "bs-30", 1).subtract(value(values, "bs-53", 1));
        return List.of(
                new StatutoryReportResponses.Check("ASSET_EQUATION", "期末资产总计=负债和所有者权益总计",
                        primaryDifference.compareTo(BigDecimal.ZERO) == 0, primaryDifference),
                new StatutoryReportResponses.Check("OPENING_EQUATION", "年初资产总计=负债和所有者权益总计",
                        comparativeDifference.compareTo(BigDecimal.ZERO) == 0, comparativeDifference));
    }

    private BigDecimal evaluate(Definition definition,
                                Map<String, ReportResponses.TrialBalanceLine> source,
                                Map<String, BigDecimal[]> calculated,
                                boolean income,
                                boolean primary) {
        return switch (definition.operation().kind()) {
            case "ACCOUNT_BALANCE" -> accountAmount(definition.operation(), source, false);
            case "ACCOUNT_ACTIVITY" -> accountAmount(definition.operation(), source, true);
            case "RECLASSIFIED_BALANCE" -> accountAmount(definition.operation(), source, false).max(ZERO);
            case "LINEAR_COMBINATION" -> definition.operation().components().stream()
                    .map(component -> value(calculated, component.key(), primary ? 0 : 1)
                            .multiply(BigDecimal.valueOf(component.factor())))
                    .reduce(ZERO, BigDecimal::add);
            default -> throw new IllegalArgumentException("Unsupported statutory formula operation: "
                    + definition.operation().kind());
        };
    }

    private BigDecimal accountAmount(Operation operation,
                                     Map<String, ReportResponses.TrialBalanceLine> source,
                                     boolean activity) {
        BigDecimal value = ZERO;
        for (String code : operation.accountCodes()) {
            ReportResponses.TrialBalanceLine line = source.get(code);
            if (line == null) {
                continue;
            }
            BigDecimal debit = activity ? line.periodDebit() : line.closingDebit();
            BigDecimal credit = activity ? line.periodCredit() : line.closingCredit();
            BigDecimal signed = "CREDIT".equals(operation.side()) ? credit.subtract(debit) : debit.subtract(credit);
            value = value.add(operation.positiveOnly() ? signed.max(ZERO) : signed);
        }
        return money(value);
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
        return value.setScale(2, java.math.RoundingMode.HALF_UP);
    }

    private static Definition account(String key, int lineNo, String name, int indent, String rowType,
                                      String kind, String side, boolean positiveOnly, String... codes) {
        return new Definition(key, lineNo, name, indent, rowType,
                new Operation(kind, side, positiveOnly, List.of(codes), List.of()));
    }

    private static Definition zero(String key, int lineNo, String name, int indent, String rowType) {
        return sum(key, lineNo, name, indent, rowType);
    }

    private static Definition sum(String key, int lineNo, String name, int indent, String rowType,
                                  Component... components) {
        return new Definition(key, lineNo, name, indent, rowType,
                new Operation("LINEAR_COMBINATION", "DEBIT", false, List.of(), List.of(components)));
    }

    private static List<GroupDefinition> incomeGroups() {
        return List.of(new GroupDefinition("FULL", "利润表", List.of(
                account("is-1", 1, "一、营业收入", 0, "SECTION", "ACCOUNT_ACTIVITY", "CREDIT", false, "5001", "5051", "5101"),
                account("is-2", 2, "减：营业成本", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5401", "5405"),
                account("is-3", 3, "营业税金及附加", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5403"),
                zero("is-4", 4, "其中：消费税", 1, "SUB_DETAIL"),
                zero("is-5", 5, "营业税", 1, "SUB_DETAIL"),
                account("is-6", 6, "城市维护建设税", 1, "SUB_DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "540301"),
                zero("is-7", 7, "资源税", 1, "SUB_DETAIL"),
                zero("is-8", 8, "土地增值税", 1, "SUB_DETAIL"),
                zero("is-9", 9, "城镇土地使用税、房产税、车船税、印花税", 1, "SUB_DETAIL"),
                account("is-10", 10, "教育费附加、矿产资源补偿费、排污费", 1, "SUB_DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "540305", "540306"),
                account("is-11", 11, "销售费用", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5601"),
                account("is-12", 12, "其中：商品维修费", 1, "SUB_DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "560101"),
                account("is-13", 13, "广告费和业务宣传费", 1, "SUB_DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "560102"),
                account("is-14", 14, "管理费用", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5602"),
                zero("is-15", 15, "其中：开办费", 1, "SUB_DETAIL"),
                zero("is-16", 16, "业务招待费", 1, "SUB_DETAIL"),
                zero("is-17", 17, "研究费用", 1, "SUB_DETAIL"),
                account("is-18", 18, "财务费用", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5603"),
                zero("is-19", 19, "其中：利息费用（收入以“-”号填列）", 1, "SUB_DETAIL"),
                account("is-20", 20, "加：投资收益（损失以“-”号填列）", 0, "DETAIL", "ACCOUNT_ACTIVITY", "CREDIT", false, "5111"),
                sum("is-21", 21, "二、营业利润（亏损以“-”号填列）", 0, "TOTAL",
                        c("is-1"), c("is-2", -1), c("is-3", -1), c("is-11", -1), c("is-14", -1), c("is-18", -1), c("is-20")),
                account("is-22", 22, "加：营业外收入", 0, "DETAIL", "ACCOUNT_ACTIVITY", "CREDIT", false, "5301"),
                zero("is-23", 23, "其中：政府补助", 1, "SUB_DETAIL"),
                account("is-24", 24, "减：营业外支出", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5711"),
                zero("is-25", 25, "其中：坏账损失", 1, "SUB_DETAIL"),
                zero("is-26", 26, "无法收回的长期债券投资损失", 1, "SUB_DETAIL"),
                zero("is-27", 27, "无法收回的长期股权投资损失", 1, "SUB_DETAIL"),
                zero("is-28", 28, "自然灾害等不可抗力因素造成的损失", 1, "SUB_DETAIL"),
                zero("is-29", 29, "税收滞纳金", 1, "SUB_DETAIL"),
                sum("is-30", 30, "三、利润总额（亏损总额以“-”号填列）", 0, "TOTAL",
                        c("is-21"), c("is-22"), c("is-24", -1)),
                account("is-31", 31, "减：所得税费用", 0, "DETAIL", "ACCOUNT_ACTIVITY", "DEBIT", false, "5801"),
                sum("is-32", 32, "四、净利润（净亏损以“-”号填列）", 0, "TOTAL",
                        c("is-30"), c("is-31", -1))
        )));
    }

    private static List<GroupDefinition> balanceGroups() {
        List<Definition> left = List.of(
                zero("bs-0", 0, "流动资产：", 0, "SECTION"),
                account("bs-1", 1, "货币资金", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1001", "1002", "1012"),
                account("bs-2", 2, "短期投资", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1101"),
                account("bs-3", 3, "应收票据", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1121"),
                account("bs-4", 4, "应收账款", 0, "DETAIL", "RECLASSIFIED_BALANCE", "DEBIT", true, "1122"),
                account("bs-5", 5, "预付账款", 0, "DETAIL", "RECLASSIFIED_BALANCE", "DEBIT", true, "1123"),
                account("bs-6", 6, "应收股利", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1131"),
                account("bs-7", 7, "应收利息", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1132"),
                account("bs-8", 8, "其他应收款", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1221"),
                account("bs-9", 9, "存货", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false,
                        "1401", "1402", "1403", "1404", "1405", "1406", "1407", "1408", "1411", "1412", "1413", "1471", "4001"),
                account("bs-10", 10, "其中：原材料", 1, "SUB_DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1403"),
                account("bs-11", 11, "在产品", 1, "SUB_DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "4001"),
                account("bs-12", 12, "库存商品", 1, "SUB_DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1405"),
                account("bs-13", 13, "周转材料", 1, "SUB_DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1411"),
                account("bs-14", 14, "其他流动资产", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1441"),
                sum("bs-15", 15, "流动资产合计", 0, "TOTAL",
                        c("bs-1"), c("bs-2"), c("bs-3"), c("bs-4"), c("bs-5"), c("bs-6"), c("bs-7"), c("bs-8"), c("bs-9"), c("bs-14")),
                zero("bs-16", 16, "长期债券投资", 0, "DETAIL"),
                zero("bs-17", 17, "长期股权投资", 0, "DETAIL"),
                account("bs-18", 18, "固定资产原价", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1601"),
                account("bs-19", 19, "减：累计折旧", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "1602"),
                sum("bs-20", 20, "固定资产账面价值", 0, "CALCULATION", c("bs-18"), c("bs-19", -1)),
                account("bs-21", 21, "在建工程", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1603"),
                account("bs-22", 22, "工程物资", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1604"),
                account("bs-23", 23, "固定资产清理", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1606"),
                account("bs-24", 24, "生产性生物资产", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1621"),
                account("bs-25", 25, "无形资产", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1701", "1702"),
                zero("bs-26", 26, "开发支出", 0, "DETAIL"),
                account("bs-27", 27, "长期待摊费用", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1801"),
                account("bs-28", 28, "其他非流动资产", 0, "DETAIL", "ACCOUNT_BALANCE", "DEBIT", false, "1901"),
                sum("bs-29", 29, "非流动资产合计", 0, "TOTAL",
                        c("bs-16"), c("bs-17"), c("bs-20"), c("bs-21"), c("bs-22"), c("bs-23"), c("bs-24"), c("bs-25"), c("bs-26"), c("bs-27"), c("bs-28")),
                sum("bs-30", 30, "资产总计", 0, "TOTAL", c("bs-15"), c("bs-29"))
        );
        List<Definition> right = List.of(
                zero("bs-31-title", 0, "流动负债：", 0, "SECTION"),
                account("bs-31", 31, "短期借款", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2001"),
                account("bs-32", 32, "应付票据", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2201"),
                account("bs-33", 33, "应付账款", 0, "DETAIL", "RECLASSIFIED_BALANCE", "CREDIT", true, "2202", "1123"),
                account("bs-34", 34, "预收账款", 0, "DETAIL", "RECLASSIFIED_BALANCE", "CREDIT", true, "2203", "1122"),
                account("bs-35", 35, "应付职工薪酬", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2211"),
                account("bs-36", 36, "应交税费", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2221"),
                account("bs-37", 37, "应付利息", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2231"),
                account("bs-38", 38, "应付利润", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2232"),
                account("bs-39", 39, "其他应付款", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2241"),
                account("bs-40", 40, "其他流动负债", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2291"),
                sum("bs-41", 41, "流动负债合计", 0, "TOTAL", c("bs-31"), c("bs-32"), c("bs-33"), c("bs-34"), c("bs-35"), c("bs-36"), c("bs-37"), c("bs-38"), c("bs-39"), c("bs-40")),
                account("bs-42", 42, "长期借款", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2501"),
                account("bs-43", 43, "长期应付款", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2701"),
                account("bs-44", 44, "递延收益", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2401"),
                account("bs-45", 45, "其他非流动负债", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "2901"),
                sum("bs-46", 46, "非流动负债合计", 0, "TOTAL", c("bs-42"), c("bs-43"), c("bs-44"), c("bs-45")),
                sum("bs-47", 47, "负债合计", 0, "TOTAL", c("bs-41"), c("bs-46")),
                zero("bs-48-title", 0, "所有者权益（或股东权益）：", 0, "SECTION"),
                account("bs-48", 48, "实收资本（或股本）", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "3001"),
                account("bs-49", 49, "资本公积", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "3002"),
                account("bs-50", 50, "盈余公积", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "3101"),
                account("bs-51", 51, "未分配利润", 0, "DETAIL", "ACCOUNT_BALANCE", "CREDIT", false, "3103", "3104"),
                sum("bs-52", 52, "所有者权益（或股东权益）合计", 0, "TOTAL", c("bs-48"), c("bs-49"), c("bs-50"), c("bs-51")),
                sum("bs-53", 53, "负债和所有者权益（或股东权益）总计", 0, "TOTAL", c("bs-47"), c("bs-52"))
        );
        return List.of(new GroupDefinition("LEFT", "资产", left), new GroupDefinition("RIGHT", "负债和所有者权益（或股东权益）", right));
    }

    private static Component c(String key) { return new Component(key, 1); }
    private static Component c(String key, int factor) { return new Component(key, factor); }

    private record GroupDefinition(String key, String title, List<Definition> rows) { }
    private record Definition(String key, int lineNo, String name, int indent, String rowType, Operation operation) { }
    private record Operation(String kind, String side, boolean positiveOnly, List<String> accountCodes, List<Component> components) { }
    private record Component(String key, int factor) { }
}
