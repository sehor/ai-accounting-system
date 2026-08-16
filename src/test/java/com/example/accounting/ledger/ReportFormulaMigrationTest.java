package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.internal.application.ReportFormulaMigrationService;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class ReportFormulaMigrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private ReportFormulaMigrationService migration;

    @Autowired
    private ReportFormulaRepository formulas;

    @Autowired
    private AccountingStandardCatalog standards;

    @Autowired
    private JdbcTemplate jdbc;

    private final FormulaParser parser = new FormulaParser();

    @Test
    void newLedgersGetCanonicalSnapshotsWithStandardPublishedRevisions() {
        UUID ledgerId = createLedger(UUID.randomUUID(), "SME", "2011-17");

        for (String code : List.of("BALANCE_SHEET", "INCOME_STATEMENT")) {
            ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code).orElseThrow();
            assertThat(snapshot.schemaVersion()).isEqualTo(1);
            assertThat(snapshot.formulaKind()).isEqualTo("FIXED_LINES");
            assertThat(snapshot.publishedVersion()).isEqualTo(1);
            ReportFormulaRepository.Revision revision =
                    formulas.findPublishedVersion(ledgerId, code, 1).orElseThrow();
            assertThat(revision.source()).isEqualTo("STANDARD");
            assertThat(parser.parse(revision.definitionJson()).schemaVersion()).isEqualTo(1);
        }
    }

    @Test
    void migratesLegacySmeAndCasSnapshotsIdempotently() {
        UUID smeId = createLedger(UUID.randomUUID(), "SME", "2011-17");
        UUID casId = createLedger(UUID.randomUUID(), "CAS", "2006-18");
        downgradeToLegacy(smeId, "SME", "2011-17");
        downgradeToLegacy(casId, "CAS", "2006-18");

        migration.migrateAll();

        assertMigrated(smeId, "FIXED_LINES", "MIGRATION");
        assertMigrated(casId, "ACCOUNT_DETAIL", "MIGRATION");

        migration.migrateAll();

        for (String code : List.of("BALANCE_SHEET", "INCOME_STATEMENT")) {
            assertThat(formulas.countPublishedVersions(smeId, code)).isEqualTo(1);
            assertThat(formulas.countPublishedVersions(casId, code)).isEqualTo(1);
            ReportFormulaRepository.Snapshot smeSnapshot =
                    formulas.findSnapshot(smeId, code).orElseThrow();
            assertThat(smeSnapshot.schemaVersion()).isEqualTo(1);
            assertThat(parser.parse(smeSnapshot.formulaJson()).schemaVersion()).isEqualTo(1);
        }
    }

    @Test
    void onlyOneDraftPerFormulaAtDatabaseLevel() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "2011-17");
        ReportFormulaRepository.Snapshot snapshot =
                formulas.findSnapshot(ledgerId, "BALANCE_SHEET").orElseThrow();

        UUID first = formulas.createDraft(snapshot.id(), snapshot.formulaJson(),
                snapshot.publishedVersion(), userId);
        assertThat(first).isNotNull();
        assertThatThrownBy(() -> formulas.createDraft(snapshot.id(), snapshot.formulaJson(),
                snapshot.publishedVersion(), userId))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void staleDraftVersionUpdateReturnsConflict() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "2011-17");
        ReportFormulaRepository.Snapshot snapshot =
                formulas.findSnapshot(ledgerId, "BALANCE_SHEET").orElseThrow();
        UUID draftId = formulas.createDraft(snapshot.id(), snapshot.formulaJson(),
                snapshot.publishedVersion(), userId);

        assertThat(formulas.updateDraft(draftId, snapshot.formulaJson(), 1, userId)).isTrue();
        assertThat(formulas.updateDraft(draftId, snapshot.formulaJson(), 1, userId)).isFalse();
        ReportFormulaRepository.Revision draft = formulas.findDraft(ledgerId, "BALANCE_SHEET").orElseThrow();
        assertThat(draft.draftVersion()).isEqualTo(2);
        assertThat(draft.lastPreviewedDraftVersion()).isNull();
    }

    @Test
    void accountsReferencedByRevisionsCannotBeHardDeleted() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "2011-17");
        LedgerResponses.Account custom = ledgers.createAccount(userId, ledgerId,
                new LedgerRequests.AccountCreate(
                        "1197", "公式引用科目", "ASSET.CASH", "CURRENT_ASSET", "DEBIT"));
        ReportFormulaRepository.Revision revision =
                formulas.findPublishedVersion(ledgerId, "BALANCE_SHEET", 1).orElseThrow();

        formulas.replaceAccountReferences(revision.id(), ledgerId, Set.of(custom.id()));

        assertThat(formulas.accountReferenced(ledgerId, "BALANCE_SHEET", custom.id())).isTrue();
        assertThatThrownBy(() -> jdbc.update("delete from ledger_account where id = ?", custom.id()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void smeCanonicalJsonKeepsLegacyCategoryArraysForDynamicReports() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "2011-17");

        ReportFormulaRepository.Snapshot balanceSheet =
                formulas.findSnapshot(ledgerId, "BALANCE_SHEET").orElseThrow();
        assertThat(balanceSheet.formulaJson()).contains("\"debitCategories\"")
                .contains("\"CURRENT_ASSET\"")
                .contains("\"creditCategories\"")
                .contains("\"EQUITY\"");
        ReportFormulaRepository.Snapshot income =
                formulas.findSnapshot(ledgerId, "INCOME_STATEMENT").orElseThrow();
        assertThat(income.formulaJson()).contains("\"revenueCategories\"")
                .contains("\"expenseCategories\"");

        // The canonical schema still round-trips; the extra fields are ignored by the domain type.
        ReportFormulaDefinition parsed = parser.parse(balanceSheet.formulaJson());
        assertThat(parsed.kind()).isEqualTo("FIXED_LINES");
        assertThat(parsed.schemaVersion()).isEqualTo(1);
    }

    private void assertMigrated(UUID ledgerId, String kind, String source) {
        for (String code : List.of("BALANCE_SHEET", "INCOME_STATEMENT")) {
            ReportFormulaRepository.Snapshot snapshot = formulas.findSnapshot(ledgerId, code).orElseThrow();
            assertThat(snapshot.schemaVersion()).isEqualTo(1);
            assertThat(snapshot.formulaKind()).isEqualTo(kind);
            ReportFormulaDefinition definition = parser.parse(snapshot.formulaJson());
            assertThat(definition.schemaVersion()).isEqualTo(1);
            assertThat(definition.kind()).isEqualTo(kind);
            ReportFormulaRepository.Revision revision =
                    formulas.findPublishedVersion(ledgerId, code, 1).orElseThrow();
            assertThat(revision.source()).isEqualTo(source);
        }
    }

    private void downgradeToLegacy(UUID ledgerId, String standardCode, String standardVersion) {
        jdbc.update("""
                delete from report_formula_revision
                where formula_id in (select id from report_formula_snapshot where ledger_id = ?)
                """, ledgerId);
        AccountingStandard.Package standard = standards.find(standardCode, standardVersion).orElseThrow();
        for (AccountingStandard.Formula formula : standard.formulas()) {
            jdbc.update("""
                    update report_formula_snapshot
                    set formula_json = ?::jsonb, formula_kind = 'LEGACY', schema_version = 0,
                        published_version = 1
                    where ledger_id = ? and code = ?
                    """, formula.definition().toString(), ledgerId, formula.code());
        }
    }

    private UUID createLedger(UUID userId, String standardCode, String standardVersion) {
        CurrentUserResolver.ResolvedUser user =
                new CurrentUserResolver.ResolvedUser(userId, "test", UUID.randomUUID().toString());
        return ledgers.create(user, new LedgerRequests.Create("公式迁移测试 " + standardCode,
                standardCode, standardVersion, "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }
}
