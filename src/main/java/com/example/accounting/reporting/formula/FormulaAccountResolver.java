package com.example.accounting.reporting.formula;

import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
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
            } else {
                accountIds.add(UUID.fromString(reference.value()));
            }
        }
        Set<UUID> result = new HashSet<>(reports.leafAccountsByStandardKeys(ledgerId, standardKeys));
        reports.leafDescendants(ledgerId, accountIds).values().forEach(result::addAll);
        return result;
    }
}
