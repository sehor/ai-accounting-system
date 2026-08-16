package com.example.accounting.ledger.internal.application;

import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.StandardFormulaConverter;
import com.example.accounting.ledger.formula.StandardFormulaValidator;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent provisioning of the statutory cash flow template for SME ledgers:
 * the sixteen detailed 会小企 03 表 items plus the fixed 22-line CASH_FLOW
 * formula.  Runs on new-ledger initialization, at startup for every existing
 * ledger and after restoring older backups.  Repeated executions (including
 * concurrent instances) produce no additional rows or versions; a custom item
 * that occupies a reserved code blocks readiness instead of being overwritten.
 * CAS ledgers are never touched.
 */
@Service
public class CashFlowTemplateProvisioner {

    private static final Set<String> LEGACY_COARSE_TEMPLATE_CODES =
            Set.of("OPERATING", "INVESTING", "FINANCING");

    private final LedgerRepository ledgers;
    private final AccountingStandardCatalog standards;
    private final AccountManagementRepository accounts;
    private final ReportFormulaRepository formulas;
    private final StandardFormulaConverter converter;
    private final StandardFormulaValidator validator;

    public CashFlowTemplateProvisioner(LedgerRepository ledgers, AccountingStandardCatalog standards,
                                       AccountManagementRepository accounts,
                                       ReportFormulaRepository formulas,
                                       StandardFormulaConverter converter,
                                       StandardFormulaValidator validator) {
        this.ledgers = ledgers;
        this.standards = standards;
        this.accounts = accounts;
        this.formulas = formulas;
        this.converter = converter;
        this.validator = validator;
    }

    @Transactional
    public void provisionAll() {
        for (UUID ledgerId : ledgers.listAllLedgerIds()) {
            provision(ledgerId);
        }
    }

    @Transactional
    public void provision(UUID ledgerId) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(ledgerId).orElse(null);
        if (ledger == null) {
            return;
        }
        String standardVersion = "v1".equals(ledger.accountingStandardVersion())
                ? "2011-17" : ledger.accountingStandardVersion();
        AccountingStandard.Package standard = standards.find(
                ledger.accountingStandardCode(), standardVersion).orElse(null);
        if (standard == null) {
            throw new IllegalStateException("Cannot provision cash flow templates of ledger " + ledgerId
                    + ": accounting standard " + ledger.accountingStandardCode() + "/"
                    + standardVersion + " is not installed");
        }
        provision(ledgerId, standard);
    }

    public void provision(UUID ledgerId, AccountingStandard.Package standard) {
        if (!"SME".equalsIgnoreCase(standard.code())) {
            return;
        }
        for (AccountingStandard.CashFlowItem item : standard.cashFlowItems()) {
            if (accounts.nonTemplateCashFlowItemExists(ledgerId, item.code())) {
                throw new IllegalStateException("Cash flow item code conflict on ledger " + ledgerId
                        + ": " + item.code() + " is already used by a custom item");
            }
            accounts.insertTemplateCashFlowItemIfAbsent(ledgerId, item.code(), item.name());
        }
        accounts.deactivateTemplateCashFlowItems(ledgerId, LEGACY_COARSE_TEMPLATE_CODES);
        if (formulas.findSnapshot(ledgerId, ReportFormulaDefinition.REPORT_CASH_FLOW).isPresent()) {
            return;
        }
        AccountingStandard.Formula formula = standard.formulas().stream()
                .filter(candidate -> ReportFormulaDefinition.REPORT_CASH_FLOW.equals(candidate.code()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "The SME standard package lacks the CASH_FLOW formula"));
        ReportFormulaDefinition canonical = converter.convert(standard, formula);
        validator.validate(standard, canonical);
        formulas.createSnapshotWithPublishedVersion(
                ledgerId, formula.code(), formula.name(), canonical.kind(),
                converter.canonicalJson(standard, formula), null);
    }
}
