package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.formula.FormulaParser;
import com.example.accounting.ledger.formula.ReportFormulaDefinition;
import com.example.accounting.ledger.internal.application.CashFlowTemplateProvisioner;
import com.example.accounting.ledger.internal.application.ReportFormulaMigrationService;
import com.example.accounting.ledger.internal.port.ReportFormulaRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;

@SpringBootTest
@Transactional
class ReportFormulaMigrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private ReportFormulaMigrationService migration;

    @Autowired
    private CashFlowTemplateProvisioner cashFlowProvisioner;

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

    @Test
    void cashFlowTemplateProvisionedIdempotentlyForExistingSmeLedgers() {
        UUID ledgerId = createLedger(UUID.randomUUID(), "SME", "2011-17");
        // Simulate a legacy ledger: no CASH_FLOW formula, only the three coarse items.
        jdbc.update("""
                delete from report_formula_revision
                where formula_id in (
                    select id from report_formula_snapshot where ledger_id = ? and code = 'CASH_FLOW')
                """, ledgerId);
        jdbc.update("delete from report_formula_snapshot where ledger_id = ? and code = 'CASH_FLOW'", ledgerId);
        jdbc.update("delete from cash_flow_item where ledger_id = ?", ledgerId);
        insertTemplateItem(ledgerId, "OPERATING", "经营活动产生的现金流量");
        insertTemplateItem(ledgerId, "INVESTING", "投资活动产生的现金流量");
        insertTemplateItem(ledgerId, "FINANCING", "筹资活动产生的现金流量");

        cashFlowProvisioner.provision(ledgerId);

        ReportFormulaRepository.Snapshot cashFlow =
                formulas.findSnapshot(ledgerId, "CASH_FLOW").orElseThrow();
        assertThat(cashFlow.schemaVersion()).isEqualTo(1);
        assertThat(cashFlow.formulaKind()).isEqualTo("FIXED_LINES");
        assertThat(cashFlow.publishedVersion()).isEqualTo(1);
        ReportFormulaDefinition definition = parser.parse(cashFlow.formulaJson());
        assertThat(definition.reportType()).isEqualTo("CASH_FLOW");
        long lineCount = definition.groups().stream().mapToLong(group -> group.lines().size()).sum();
        assertThat(lineCount).isEqualTo(22);
        assertThat(countItems(ledgerId, "ACTIVE")).isEqualTo(16);
        assertThat(countItems(ledgerId, "INACTIVE")).isEqualTo(3);
        assertThat(countItemsByCode(ledgerId, "OPERATING")).isEqualTo(1);

        // Second run produces no extra rows or versions.
        cashFlowProvisioner.provision(ledgerId);
        assertThat(countItems(ledgerId, "ACTIVE")).isEqualTo(16);
        assertThat(formulas.countPublishedVersions(ledgerId, "CASH_FLOW")).isEqualTo(1);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentFormulaCreationKeepsOneSnapshotAndPublishedRevision() throws Exception {
        UUID ledgerId = createLedger(UUID.randomUUID(), "SME", "2011-17");
        ReportFormulaRepository.Snapshot source =
                formulas.findSnapshot(ledgerId, "CASH_FLOW").orElseThrow();
        jdbc.update("delete from report_formula_revision where formula_id = ?", source.id());
        jdbc.update("delete from report_formula_snapshot where id = ?", source.id());

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var task = (java.util.concurrent.Callable<Void>) () -> {
                ready.countDown();
                start.await();
                formulas.createSnapshotWithPublishedVersion(
                        ledgerId, "CASH_FLOW", source.name(), source.formulaKind(),
                        source.formulaJson(), null);
                return null;
            };
            var first = executor.submit(task);
            var second = executor.submit(task);
            ready.await();
            start.countDown();
            first.get();
            second.get();
        }

        assertThat(jdbc.queryForObject("""
                select count(*) from report_formula_snapshot
                where ledger_id = ? and code = 'CASH_FLOW'
                """, Long.class, ledgerId)).isEqualTo(1L);
        assertThat(formulas.countPublishedVersions(ledgerId, "CASH_FLOW")).isEqualTo(1);
    }

    @Test
    void customItemConflictingWithReservedCashFlowCodeBlocksProvisioning() {
        UUID ledgerId = createLedger(UUID.randomUUID(), "SME", "2011-17");
        jdbc.update("delete from cash_flow_item where ledger_id = ? and code = 'SME_CF_01_SALES_RECEIPTS'",
                ledgerId);
        insertCustomItem(ledgerId, "SME_CF_01_SALES_RECEIPTS", "自定义销售");

        assertThatThrownBy(() -> cashFlowProvisioner.provision(ledgerId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SME_CF_01_SALES_RECEIPTS")
                .hasMessageContaining(ledgerId.toString());
    }

    @Test
    void casLedgersAreNotProvisioned() {
        UUID ledgerId = createLedger(UUID.randomUUID(), "CAS", "2006-18");
        long itemsBefore = jdbc.queryForObject(
                "select count(*) from cash_flow_item where ledger_id = ?", Long.class, ledgerId);

        cashFlowProvisioner.provision(ledgerId);

        assertThat(formulas.findSnapshot(ledgerId, "CASH_FLOW")).isEmpty();
        assertThat(jdbc.queryForObject(
                "select count(*) from cash_flow_item where ledger_id = ?", Long.class, ledgerId))
                .isEqualTo(itemsBefore);
    }

    private void insertTemplateItem(UUID ledgerId, String code, String name) {
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, is_template)
                values (?, ?, ?, ?, true)
                """, UUID.randomUUID(), ledgerId, code, name);
    }

    private void insertCustomItem(UUID ledgerId, String code, String name) {
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, is_template)
                values (?, ?, ?, ?, false)
                """, UUID.randomUUID(), ledgerId, code, name);
    }

    private long countItems(UUID ledgerId, String status) {
        return jdbc.queryForObject(
                "select count(*) from cash_flow_item where ledger_id = ? and status = ?",
                Long.class, ledgerId, status);
    }

    private long countItemsByCode(UUID ledgerId, String code) {
        return jdbc.queryForObject(
                "select count(*) from cash_flow_item where ledger_id = ? and code = ?",
                Long.class, ledgerId, code);
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
