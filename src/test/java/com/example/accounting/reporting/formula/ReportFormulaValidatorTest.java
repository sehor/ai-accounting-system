package com.example.accounting.reporting.formula;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountAmountExpression;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.AccountReference;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.DetailRule;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaGroup;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.FormulaLine;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LineComponent;
import com.example.accounting.ledger.formula.ReportFormulaDefinition.LinearCombinationExpression;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class ReportFormulaValidatorTest {

    @Autowired
    private ReportFormulaValidator validator;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private com.example.accounting.ledger.internal.port.ReportFormulaRepository formulas;

    @Autowired
    private JdbcTemplate jdbc;

    private final FormulaParser parser = new FormulaParser();

    @Test
    void acceptsCanonicalSmeAndCasDefinitions() {
        UUID smeLedger = createLedger("SME", "2011-17");
        UUID casLedger = createLedger("CAS", "2006-18");

        assertThat(validator.validate(smeDefinition(smeLedger, "BALANCE_SHEET"), smeLedger)).isEmpty();
        assertThat(validator.validate(smeDefinition(smeLedger, "INCOME_STATEMENT"), smeLedger)).isEmpty();
        assertThat(validator.validate(casDefinition(casLedger, "BALANCE_SHEET"), casLedger)).isEmpty();
        assertThat(validator.validate(casDefinition(casLedger, "INCOME_STATEMENT"), casLedger)).isEmpty();
    }

    @Test
    void rejectsDuplicateAndBackwardLineReferences() {
        UUID ledgerId = createLedger("SME", "2011-17");
        ReportFormulaDefinition definition = smeDefinition(ledgerId, "BALANCE_SHEET");

        ReportFormulaDefinition duplicateKeys = replaceLine(definition, "bs-1",
                new FormulaLine("bs-2", 1, 0, "DETAIL", "重复行",
                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of(
                                new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH")))));
        assertThat(validator.validate(duplicateKeys, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("LINE_KEY_DUPLICATE");

        // bs-1 combining bs-53 (a later line) is a backward-reference violation.
        ReportFormulaDefinition backward = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "后向引用",
                        new LinearCombinationExpression(List.of(new LineComponent("bs-53", 1)))));
        assertThat(validator.validate(backward, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("BACKWARD_REFERENCE_REQUIRED");
    }

    @Test
    void rejectsInvalidOperationsFactorsAndLimits() {
        UUID ledgerId = createLedger("SME", "2011-17");
        ReportFormulaDefinition definition = smeDefinition(ledgerId, "BALANCE_SHEET");

        ReportFormulaDefinition badOperation = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "非法操作",
                        new AccountAmountExpression("MAGIC", "DEBIT", List.of(
                                new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH")))));
        assertThat(validator.validate(badOperation, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("OPERATION_INVALID");

        ReportFormulaDefinition badFactor = replaceLine(definition, "bs-20",
                new FormulaLine("bs-20", 20, 0, "CALCULATION", "非法因子",
                        new LinearCombinationExpression(List.of(new LineComponent("bs-18", 2)))));
        assertThat(validator.validate(badFactor, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("FACTOR_INVALID");

        ReportFormulaDefinition longName = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "x".repeat(201),
                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of())));
        assertThat(validator.validate(longName, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("NAME_INVALID");

        List<AccountReference> tooMany = new ArrayList<>();
        for (int index = 0; index < 101; index++) {
            tooMany.add(new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH"));
        }
        ReportFormulaDefinition overLimit = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "超限",
                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", tooMany)));
        assertThat(validator.validate(overLimit, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("LIMIT_EXCEEDED");
    }

    @Test
    void rejectsCrossLedgerAndOverlappingAccountReferences() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "2011-17");
        UUID otherLedger = createLedger("SME", "2011-17");

        ReportFormulaDefinition definition = smeDefinition(ledgerId, "BALANCE_SHEET");
        ReportFormulaDefinition crossLedger = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "跨账套",
                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of(
                                new AccountReference("ACCOUNT_ID", accountId(otherLedger, "1001").toString())))));
        assertThat(validator.validate(crossLedger, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("ACCOUNT_OUTSIDE_LEDGER");

        // Parent 1001 plus its child 100101 in one expression overlaps after expansion.
        UUID cashParent = accountId(ledgerId, "1001");
        LedgerResponses.Account child = ledgers.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate("100101", "现金子科目", "CURRENT_ASSET", "DEBIT"));
        ReportFormulaDefinition overlapping = replaceLine(definition, "bs-1",
                new FormulaLine("bs-1", 1, 0, "DETAIL", "重叠取数",
                        new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of(
                                new AccountReference("ACCOUNT_ID", cashParent.toString()),
                                new AccountReference("ACCOUNT_ID", child.id().toString())))));
        assertThat(validator.validate(overlapping, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("OVERLAPPING_REFERENCES");
    }

    @Test
    void rejectsCasSideConflicts() {
        UUID ledgerId = createLedger("CAS", "2006-18");
        ReportFormulaDefinition definition = casDefinition(ledgerId, "BALANCE_SHEET");
        List<DetailRule> rules = new ArrayList<>(definition.rules());
        rules.add(new DetailRule("CONFLICT", "CREDIT", List.of("CURRENT_ASSET"), List.of()));

        ReportFormulaDefinition conflicted = new ReportFormulaDefinition(
                definition.schemaVersion(), definition.kind(), definition.reportType(),
                definition.templateCode(), definition.columnPolicy(), definition.groups(),
                rules, definition.checks());
        assertThat(validator.validate(conflicted, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("SIDE_CONFLICT");
    }

    @Test
    void rejectsLockedStructureChangesAgainstBase() {
        UUID ledgerId = createLedger("SME", "2011-17");
        ReportFormulaDefinition base = smeDefinition(ledgerId, "BALANCE_SHEET");
        List<FormulaLine> lines = new ArrayList<>(base.groups().get(0).lines());
        lines.add(new FormulaLine("bs-99", 99, 0, "DETAIL", "新行",
                new AccountAmountExpression("ACCOUNT_BALANCE", "DEBIT", List.of(
                        new AccountReference("STANDARD_ACCOUNT_KEY", "ASSET.CASH")))));
        List<FormulaGroup> groups = new ArrayList<>(base.groups());
        groups.set(0, new FormulaGroup(groups.get(0).key(), groups.get(0).title(), lines));
        ReportFormulaDefinition edited = new ReportFormulaDefinition(
                base.schemaVersion(), base.kind(), base.reportType(), base.templateCode(),
                base.columnPolicy(), groups, base.rules(), base.checks());

        assertThat(validator.validate(edited, base, ledgerId))
                .extracting(ReportFormulaValidator.FormulaIssue::code)
                .contains("STRUCTURE_LOCKED");
    }

    private ReportFormulaDefinition smeDefinition(UUID ledgerId, String code) {
        return parser.parse(formulas.findSnapshot(ledgerId, code).orElseThrow().formulaJson());
    }

    private ReportFormulaDefinition casDefinition(UUID ledgerId, String code) {
        return parser.parse(formulas.findSnapshot(ledgerId, code).orElseThrow().formulaJson());
    }

    private ReportFormulaDefinition replaceLine(ReportFormulaDefinition definition,
                                                String lineKey, FormulaLine replacement) {
        List<FormulaGroup> groups = new ArrayList<>();
        for (FormulaGroup group : definition.groups()) {
            List<FormulaLine> lines = new ArrayList<>();
            for (FormulaLine line : group.lines()) {
                lines.add(line.key().equals(lineKey) ? replacement : line);
            }
            groups.add(new FormulaGroup(group.key(), group.title(), lines));
        }
        return new ReportFormulaDefinition(definition.schemaVersion(), definition.kind(),
                definition.reportType(), definition.templateCode(), definition.columnPolicy(),
                groups, definition.rules(), definition.checks());
    }

    private UUID createLedger(String standardCode, String standardVersion) {
        return createLedger(UUID.randomUUID(), standardCode, standardVersion);
    }

    private UUID createLedger(UUID userId, String standardCode, String standardVersion) {
        CurrentUserResolver.ResolvedUser user =
                new CurrentUserResolver.ResolvedUser(userId, "test", UUID.randomUUID().toString());
        return ledgers.create(user, new LedgerRequests.Create("公式校验测试 " + standardCode,
                standardCode, standardVersion, "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }

    private UUID accountId(UUID ledgerId, String code) {
        return jdbc.queryForObject(
                "select id from ledger_account where ledger_id = ? and code = ?", UUID.class, ledgerId, code);
    }
}
