package com.example.accounting.reporting.formula;

import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Expands formula account references to concrete leaf account ids of one ledger.
 * Standard keys resolve to all mapped leaf accounts; concrete parent accounts
 * resolve to all of their leaf descendants via one parameterized recursive query.
 */
@Component
public class FormulaAccountResolver {

    private final ReportingRepository reports;

    public FormulaAccountResolver(ReportingRepository reports) {
        this.reports = reports;
    }

    public Set<UUID> expandToLeafIds(UUID ledgerId, List<AccountReference> references) {
        Set<String> standardKeys = new HashSet<>();
        Set<UUID> accountIds = new HashSet<>();
        for (AccountReference reference : references) {
            if (reference == null || reference.value() == null || reference.value().isBlank()) {
                continue;
            }
            if (ReportFormulaDefinition.REF_STANDARD_ACCOUNT_KEY.equals(reference.type())) {
                standardKeys.add(reference.value());
            } else if (ReportFormulaDefinition.REF_ACCOUNT_ID.equals(reference.type())) {
                try {
                    accountIds.add(UUID.fromString(reference.value()));
                } catch (IllegalArgumentException ignored) {
                    // The validator reports malformed UUIDs; expansion stays total for issue collection.
                }
            }
        }
        Set<UUID> result = new HashSet<>(reports.leafAccountsByStandardKeys(ledgerId, standardKeys));
        reports.leafDescendants(ledgerId, accountIds).values().forEach(result::addAll);
        return result;
    }

    /** Expands both category matches and explicit references using evaluator semantics. */
    public Set<UUID> expandRuleToLeafIds(UUID ledgerId, DetailRule rule) {
        Set<UUID> result = new HashSet<>(reports.leafAccountsByCategories(ledgerId, rule.categories()));
        result.addAll(expandToLeafIds(ledgerId, rule.accounts()));
        return result;
    }
}
