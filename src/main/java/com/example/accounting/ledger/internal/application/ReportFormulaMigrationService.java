package com.example.accounting.ledger.internal.application;

import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.StandardFormulaConverter;
import com.example.accounting.ledger.formula.StandardFormulaValidator;
import com.example.accounting.ledger.internal.port.LedgerRepository;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Idempotent startup migration of pre-editor report formula snapshots into the
 * canonical schema-1 form.  SME snapshots are rebuilt from the installed standard
 * package; CAS snapshots are converted from their legacy category arrays.  Any
 * formula that cannot migrate throws and blocks application readiness; the legacy
 * path is never kept as a runtime fallback.
 */
@Service
public class ReportFormulaMigrationService {

    private static final List<String> FORMULA_CODES =
            List.of(ReportFormulaDefinition.REPORT_BALANCE_SHEET, ReportFormulaDefinition.REPORT_INCOME_STATEMENT);

    private final LedgerRepository ledgers;
    private final ReportFormulaRepository formulas;
    private final AccountingStandardCatalog standards;
    private final StandardFormulaConverter converter;
    private final StandardFormulaValidator validator;
    private final FormulaParser parser;

    public ReportFormulaMigrationService(LedgerRepository ledgers, ReportFormulaRepository formulas,
                                         AccountingStandardCatalog standards,
                                         StandardFormulaConverter converter,
                                         StandardFormulaValidator validator,
                                         FormulaParser parser) {
        this.ledgers = ledgers;
        this.formulas = formulas;
        this.standards = standards;
        this.converter = converter;
        this.validator = validator;
        this.parser = parser;
    }

    @Transactional
    public void migrateAll() {
        for (UUID ledgerId : ledgers.listAllLedgerIds()) {
            migrateLedger(ledgerId);
        }
    }

    @Transactional
    public void migrateLedger(UUID ledgerId) {
        LedgerResponses.Ledger ledger = ledgers.findLedger(ledgerId).orElse(null);
        if (ledger == null) {
            return;
        }
        String standardCode = ledger.accountingStandardCode();
        String standardVersion = "v1".equals(ledger.accountingStandardVersion())
                ? "2011-17" : ledger.accountingStandardVersion();
        AccountingStandard.Package standard = standards.find(standardCode, standardVersion).orElse(null);
        if (standard == null) {
            throw new IllegalStateException("Cannot migrate report formulas of ledger " + ledgerId
                    + ": accounting standard " + standardCode + "/" + standardVersion + " is not installed");
        }
        for (String code : FORMULA_CODES) {
            ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code).orElse(null);
            if (snapshot == null) {
                continue;
            }
            if (snapshot.schemaVersion() == 1 && formulas.publishedVersionExists(ledgerId, code, 1)) {
                continue;
            }
            ReportFormulaDefinition canonical;
            String canonicalJson;
            if ("SME".equalsIgnoreCase(standardCode)) {
                AccountingStandard.Formula formula = standard.formulas().stream()
                        .filter(candidate -> code.equals(candidate.code())).findFirst()
                        .orElseThrow(() -> new IllegalStateException(
                                "The " + standardCode + " standard package lacks formula " + code));
                canonical = converter.convert(standard, formula);
                validator.validate(standard, canonical);
                canonicalJson = converter.canonicalJson(standard, formula);
            } else {
                canonical = converter.convertCasLegacy(parser.readTree(snapshot.formulaJson()), code);
                validator.validate(standard, canonical);
                canonicalJson = parser.write(canonical);
            }
            formulas.updateSnapshotDefinition(snapshot.id(), canonical.kind(), canonicalJson, null);
            formulas.insertPublished(snapshot.id(), canonicalJson, 0, 1, "MIGRATION", null, null);
        }
    }
}
