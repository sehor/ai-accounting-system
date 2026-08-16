package com.example.accounting.reporting.internal.application;

import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.CashFlowItemAmountExpression;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import com.example.accounting.reporting.CashFlowClassificationReader;
import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.formula.FormulaAccountResolver;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Default projection of the published CASH_FLOW formula into the voucher-side contract. */
@Component
public class DefaultCashFlowClassificationReader implements CashFlowClassificationReader {

    private final ReportingRepository reports;
    private final ReportFormulaRepository formulas;
    private final FormulaParser parser;
    private final FormulaAccountResolver resolver;
    private final AccountManagementRepository accounts;

    public DefaultCashFlowClassificationReader(
            ReportingRepository reports,
            ReportFormulaRepository formulas,
            FormulaParser parser,
            FormulaAccountResolver resolver,
            AccountManagementRepository accounts) {
        this.reports = reports;
        this.formulas = formulas;
        this.parser = parser;
        this.resolver = resolver;
        this.accounts = accounts;
    }

    @Override
    public Contract contract(UUID ledgerId) {
        ReportResponses.LedgerProfile profile = reports.ledgerProfile(ledgerId);
        if (!"SME".equalsIgnoreCase(profile.accountingStandardCode())) {
            return Contract.none();
        }
        ReportFormulaDefinition definition = publishedDefinition(ledgerId);
        if (definition == null) {
            return Contract.none();
        }
        Set<AccountReference> references = new LinkedHashSet<>();
        Set<String> codes = new LinkedHashSet<>();
        for (ReportFormulaDefinition.FormulaGroup group : definition.groups()) {
            for (ReportFormulaDefinition.FormulaLine line : group.lines()) {
                if (line.expression() instanceof CashFlowItemAmountExpression cashFlow) {
                    codes.addAll(cashFlow.itemCodes());
                    references.addAll(cashFlow.cashAccounts());
                } else if (line.expression() instanceof ReportFormulaDefinition.AccountAmountExpression account) {
                    references.addAll(account.accounts());
                }
            }
        }
        Map<UUID, CashFlowItemState> items = new LinkedHashMap<>();
        accounts.cashFlowItems(ledgerId).forEach(item -> items.put(item.id(),
                new CashFlowItemState(item.code(), item.status())));
        return new Contract(true, resolver.expandToLeafIds(ledgerId, List.copyOf(references)),
                codes, items);
    }

    private ReportFormulaDefinition publishedDefinition(UUID ledgerId) {
        ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(
                ledgerId, ReportFormulaDefinition.REPORT_CASH_FLOW).orElse(null);
        return snapshot == null ? null : parser.parse(snapshot.formulaJson());
    }
}
