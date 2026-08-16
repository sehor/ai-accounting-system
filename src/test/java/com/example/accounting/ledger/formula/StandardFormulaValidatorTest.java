package com.example.accounting.ledger.formula;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.ledger.AccountingStandard;
import com.example.accounting.ledger.AccountingStandardCatalog;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class StandardFormulaValidatorTest {

    private final AccountingStandardCatalog catalog =
            new AccountingStandardCatalog(new ObjectMapper().findAndRegisterModules());
    private final StandardFormulaConverter converter = new StandardFormulaConverter();
    private final StandardFormulaValidator validator = new StandardFormulaValidator();

    @Test
    void rejectsDuplicateLineKeys() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        FormulaGroup group = definition.groups().get(0);
        FormulaLine duplicate = new FormulaLine(group.lines().get(0).key(), 99, 0, "DETAIL",
                "duplicate", emptyCombination());
        List<FormulaLine> lines = new ArrayList<>(group.lines());
        lines.add(duplicate);
        ReportFormulaDefinition mutated = withGroups(definition,
                List.of(new FormulaGroup(group.key(), group.title(), lines)));
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Duplicate report formula line keys");
    }

    @Test
    void rejectsUnknownStandardAccountKey() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        FormulaLine line = definition.groups().get(0).lines().get(1);
        AccountAmountExpression bad = new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT",
                List.of(new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.DOES_NOT_EXIST")));
        ReportFormulaDefinition mutated = replaceLine(definition, line.key(),
                new FormulaLine(line.key(), line.lineNo(), line.indent(), line.rowType(), line.name(), bad));
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown standard account key ASSET.DOES_NOT_EXIST");
    }

    @Test
    void rejectsInvalidOperation() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        FormulaLine line = definition.groups().get(0).lines().get(1);
        AccountAmountExpression bad = new AccountAmountExpression("TOTALLY_BOGUS", "DEBIT",
                List.of(new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH")));
        ReportFormulaDefinition mutated = replaceLine(definition, line.key(),
                new FormulaLine(line.key(), line.lineNo(), line.indent(), line.rowType(), line.name(), bad));
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported account amount operation TOTALLY_BOGUS");
    }

    @Test
    void rejectsWrongKindShape() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        // FIXED_LINES definition must not carry detail rules.
        ReportFormulaDefinition mutated = new ReportFormulaDefinition(
                definition.schemaVersion(), definition.kind(), definition.reportType(),
                definition.templateCode(), definition.columnPolicy(), definition.groups(),
                List.of(new DetailRule("R1", "DEBIT", List.of("CURRENT_ASSET"), List.of())),
                definition.checks());
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("A FIXED_LINES formula must not contain detail rules");
    }

    @Test
    void rejectsUnknownComponentReferences() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        FormulaLine line = definition.groups().get(0).lines().get(1);
        LinearCombinationExpression bad = new LinearCombinationExpression(
                List.of(new LineComponent("bs-999", 1)));
        ReportFormulaDefinition mutated = replaceLine(definition, line.key(),
                new FormulaLine(line.key(), line.lineNo(), line.indent(), line.rowType(), line.name(), bad));
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("references unknown line bs-999");
    }

    @Test
    void rejectsInvalidFactorAndSchemaVersion() {
        ReportFormulaDefinition definition = smeDefinition("BALANCE_SHEET");
        FormulaLine line = definition.groups().get(0).lines().stream()
                .filter(candidate -> "bs-20".equals(candidate.key())).findFirst().orElseThrow();
        LinearCombinationExpression bad = new LinearCombinationExpression(
                List.of(new LineComponent("bs-18", 2)));
        ReportFormulaDefinition mutated = replaceLine(definition, line.key(),
                new FormulaLine(line.key(), line.lineNo(), line.indent(), line.rowType(), line.name(), bad));
        assertThatThrownBy(() -> validator.validate(standard("SME"), mutated))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invalid factor");

        ReportFormulaDefinition wrongVersion = new ReportFormulaDefinition(
                2, definition.kind(), definition.reportType(), definition.templateCode(),
                definition.columnPolicy(), definition.groups(), definition.rules(), definition.checks());
        assertThatThrownBy(() -> validator.validate(standard("SME"), wrongVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schema version");
    }

    @Test
    void standardPackagesPassStartupValidation() {
        validator.validateAll(standard("SME"));
        validator.validateAll(standard("CAS"));
    }

    private AccountingStandard.Package standard(String code) {
        return catalog.find(code, "SME".equals(code) ? "2011-17" : "2006-18").orElseThrow();
    }

    private ReportFormulaDefinition smeDefinition(String code) {
        AccountingStandard.Package sme = standard("SME");
        return converter.convert(sme, sme.formulas().stream()
                .filter(formula -> code.equals(formula.code())).findFirst().orElseThrow());
    }

    private ReportFormulaDefinition replaceLine(
            ReportFormulaDefinition definition, String lineKey, FormulaLine replacement) {
        List<FormulaGroup> groups = new ArrayList<>();
        for (FormulaGroup group : definition.groups()) {
            List<FormulaLine> lines = new ArrayList<>();
            for (FormulaLine line : group.lines()) {
                lines.add(line.key().equals(lineKey) ? replacement : line);
            }
            groups.add(new FormulaGroup(group.key(), group.title(), lines));
        }
        return withGroups(definition, groups);
    }

    private ReportFormulaDefinition withGroups(
            ReportFormulaDefinition definition, List<FormulaGroup> groups) {
        return new ReportFormulaDefinition(definition.schemaVersion(), definition.kind(),
                definition.reportType(), definition.templateCode(), definition.columnPolicy(),
                groups, definition.rules(), definition.checks());
    }

    private LinearCombinationExpression emptyCombination() {
        return new LinearCombinationExpression(List.of());
    }
}
