package com.example.accounting.reporting.formula;

import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AmountBasis;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.CashFlowDirection;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.CashFlowItemAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaCheck;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Generic report formula evaluator.  Fixed-line (SME) definitions evaluate each
 * line against the primary and comparative amount sources; detail (CAS)
 * definitions match every leaf account against the rules.  Column semantics come
 * from {@code definition.columnPolicy} — never from template names — and the
 * evaluator holds no fixed template line keys.
 */
@Component
public class ReportFormulaEvaluator {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final FormulaAccountResolver resolver;

    public ReportFormulaEvaluator(FormulaAccountResolver resolver) {
        this.resolver = resolver;
    }

    public record FixedLinesMetadata(
            String reportType, String standardCode, String standardVersion, String periodCode,
            String primaryColumn, String comparativeColumn) {
    }

    public StatutoryReportResponses.Statement evaluateFixedLines(
            UUID ledgerId, ReportFormulaDefinition definition,
            List<FormulaAccountAmount> primarySource, List<FormulaAccountAmount> comparativeSource,
            FixedLinesMetadata metadata) {
        return evaluateFixedLines(ledgerId, definition, primarySource, comparativeSource,
                CashFlowSource.empty(), CashFlowSource.empty(), metadata);
    }

    public StatutoryReportResponses.Statement evaluateFixedLines(
            UUID ledgerId, ReportFormulaDefinition definition,
            List<FormulaAccountAmount> primarySource, List<FormulaAccountAmount> comparativeSource,
            CashFlowSource primaryFlows, CashFlowSource comparativeFlows,
            FixedLinesMetadata metadata) {
        Map<UUID, FormulaAccountAmount> primary = byAccountId(primarySource);
        Map<UUID, FormulaAccountAmount> comparative = byAccountId(comparativeSource);
        Map<String, BigDecimal[]> calculated = new LinkedHashMap<>();
        List<StatutoryReportResponses.Group> groups = new ArrayList<>();
        for (FormulaGroup group : definition.groups()) {
            List<StatutoryReportResponses.Line> rows = new ArrayList<>();
            for (FormulaLine line : group.lines()) {
                BigDecimal primaryAmount = evaluate(line.expression(), ledgerId, primary,
                        primaryFlows, calculated, definition.columnPolicy().primary(), 0);
                BigDecimal comparativeAmount = evaluate(line.expression(), ledgerId, comparative,
                        comparativeFlows, calculated, definition.columnPolicy().comparative(), 1);
                calculated.put(line.key(), new BigDecimal[]{primaryAmount, comparativeAmount});
                rows.add(new StatutoryReportResponses.Line(
                        line.key(), line.lineNo(), line.name(), line.indent(), line.rowType(),
                        primaryAmount, comparativeAmount));
            }
            groups.add(new StatutoryReportResponses.Group(group.key(), group.title(), rows));
        }
        List<StatutoryReportResponses.Check> checks = evaluateChecks(definition.checks(), calculated);
        return new StatutoryReportResponses.Statement(
                metadata.reportType(), definition.templateCode(), metadata.standardCode(),
                metadata.standardVersion(), metadata.periodCode(),
                metadata.primaryColumn(), metadata.comparativeColumn(), groups, checks);
    }

    public ReportResponses.Statement evaluateAccountDetail(
            UUID ledgerId, ReportFormulaDefinition definition, List<FormulaAccountAmount> source) {
        Map<UUID, String> sideByLeaf = new HashMap<>();
        for (DetailRule rule : definition.rules()) {
            Set<UUID> matched = matchRuleLeaves(ledgerId, rule, source);
            for (UUID leaf : matched) {
                // Multiple rules with the same side match the leaf only once.
                sideByLeaf.putIfAbsent(leaf, rule.side());
            }
        }
        List<ReportResponses.StatementLine> lines = source.stream()
                .filter(amount -> sideByLeaf.containsKey(amount.accountId()))
                .map(amount -> new ReportResponses.StatementLine(amount.code(), amount.name(),
                        signed(amount, sideByLeaf.get(amount.accountId()),
                                definition.columnPolicy().primary())))
                .sorted(Comparator.comparing(ReportResponses.StatementLine::code))
                .toList();
        return new ReportResponses.Statement(lines.size(), lines);
    }

    private Set<UUID> matchRuleLeaves(UUID ledgerId, DetailRule rule, List<FormulaAccountAmount> source) {
        Set<UUID> matched = new java.util.HashSet<>();
        for (FormulaAccountAmount amount : source) {
            if (rule.categories().contains(amount.category())) {
                matched.add(amount.accountId());
            }
        }
        matched.addAll(resolver.expandToLeafIds(ledgerId, rule.accounts()));
        matched.retainAll(source.stream().map(FormulaAccountAmount::accountId).collect(
                java.util.stream.Collectors.toSet()));
        return matched;
    }

    private BigDecimal evaluate(ReportFormulaDefinition.LineExpression expression, UUID ledgerId,
                                Map<UUID, FormulaAccountAmount> source, CashFlowSource flows,
                                Map<String, BigDecimal[]> calculated, AmountBasis basis, int column) {
        if (expression instanceof CashFlowItemAmountExpression cashFlow) {
            BigDecimal debit = sumAmount(cashFlow.itemCodes(), flows.debit());
            BigDecimal credit = sumAmount(cashFlow.itemCodes(), flows.credit());
            BigDecimal value = switch (cashFlow.direction()) {
                case INFLOW -> debit.subtract(credit);
                case OUTFLOW -> credit.subtract(debit);
                case NET -> debit.subtract(credit);
            };
            return money(value);
        }
        if (expression instanceof AccountAmountExpression accountAmount) {
            AmountBasis effectiveBasis = accountAmount.basis() != null
                    ? accountAmount.basis() : basis;
            Set<UUID> expanded = resolver.expandToLeafIds(ledgerId, accountAmount.accounts());
            BigDecimal value = ZERO;
            for (FormulaAccountAmount amount : source.values()) {
                if (!expanded.contains(amount.accountId())) {
                    continue;
                }
                BigDecimal debit = effectiveBasis == AmountBasis.CLOSING ? amount.closingDebit()
                        : effectiveBasis == AmountBasis.ACTIVITY ? amount.periodDebit() : amount.openingDebit();
                BigDecimal credit = effectiveBasis == AmountBasis.CLOSING ? amount.closingCredit()
                        : effectiveBasis == AmountBasis.ACTIVITY ? amount.periodCredit() : amount.openingCredit();
                BigDecimal signed = ReportFormulaDefinition.SIDE_CREDIT.equals(accountAmount.side())
                        ? credit.subtract(debit) : debit.subtract(credit);
                value = value.add(signed);
            }
            return money(value);
        }
        if (expression instanceof LinearCombinationExpression combination) {
            BigDecimal value = ZERO;
            for (LineComponent component : combination.components()) {
                BigDecimal[] pair = calculated.get(component.lineKey());
                if (pair != null) {
                    value = value.add(pair[column].multiply(BigDecimal.valueOf(component.factor())));
                }
            }
            return money(value);
        }
        throw new IllegalArgumentException("Unsupported formula expression " + expression);
    }

    private static BigDecimal sumAmount(List<String> itemCodes, Map<String, BigDecimal> totals) {
        BigDecimal value = ZERO;
        for (String itemCode : itemCodes) {
            BigDecimal amount = totals.get(itemCode);
            if (amount != null) {
                value = value.add(amount);
            }
        }
        return value;
    }

    private List<StatutoryReportResponses.Check> evaluateChecks(
            List<FormulaCheck> checks, Map<String, BigDecimal[]> calculated) {
        List<StatutoryReportResponses.Check> result = new ArrayList<>();
        for (FormulaCheck check : checks) {
            int column = check.column() == ReportFormulaDefinition.CheckColumn.COMPARATIVE ? 1 : 0;
            BigDecimal difference = value(calculated, check.leftLineKey(), column)
                    .subtract(rightValue(check, calculated, column));
            result.add(new StatutoryReportResponses.Check(
                    check.code(), check.name(), difference.compareTo(BigDecimal.ZERO) == 0, difference));
        }
        return result;
    }

    private static BigDecimal rightValue(
            FormulaCheck check, Map<String, BigDecimal[]> calculated, int column) {
        if (check.hasRightComponents()) {
            BigDecimal total = ZERO;
            for (LineComponent component : check.rightComponents()) {
                total = total.add(value(calculated, component.lineKey(), column)
                        .multiply(BigDecimal.valueOf(component.factor())));
            }
            return total;
        }
        return value(calculated, check.rightLineKey(), column);
    }

    private BigDecimal signed(FormulaAccountAmount amount, String side, AmountBasis basis) {
        BigDecimal debit = basis == AmountBasis.CLOSING ? amount.closingDebit()
                : basis == AmountBasis.ACTIVITY ? amount.periodDebit() : amount.openingDebit();
        BigDecimal credit = basis == AmountBasis.CLOSING ? amount.closingCredit()
                : basis == AmountBasis.ACTIVITY ? amount.periodCredit() : amount.openingCredit();
        return money(ReportFormulaDefinition.SIDE_CREDIT.equals(side)
                ? credit.subtract(debit) : debit.subtract(credit));
    }

    private static BigDecimal value(Map<String, BigDecimal[]> calculated, String key, int index) {
        BigDecimal[] pair = calculated.get(key);
        return pair == null ? ZERO : pair[index];
    }

    private static Map<UUID, FormulaAccountAmount> byAccountId(List<FormulaAccountAmount> amounts) {
        Map<UUID, FormulaAccountAmount> result = new HashMap<>();
        amounts.forEach(amount -> result.put(amount.accountId(), amount));
        return result;
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }
}
