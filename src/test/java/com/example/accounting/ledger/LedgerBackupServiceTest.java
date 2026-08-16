package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.agent.AccountingExperienceService;
import com.example.accounting.agent.ExperienceRequests;
import com.example.accounting.agent.ExperienceScope;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.identity.UserType;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HexFormat;
import java.util.zip.ZipInputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(properties = "storage.local.root=target/ledger-backup-test-files")
@Transactional
class LedgerBackupServiceTest {

    private static final List<String> BUSINESS_TABLES = List.of(
            "cash_flow_item", "dimension_type", "dimension_value", "dimension_combination",
            "dimension_combination_member", "ledger_account",
            "ledger_account_dimension", "accounting_period", "opening_balance", "opening_balance_dimension", "voucher",
            "voucher_line", "voucher_line_dimension", "voucher_approval", "period_action_audit",
            "report_formula_snapshot", "report_formula_revision", "report_formula_account_reference",
            "audit_revision", "document", "document_extraction",
            "agent_tool_audit", "accounting_experience", "fixed_asset_category", "fixed_asset",
            "fixed_asset_change", "fixed_asset_depreciation_run", "fixed_asset_depreciation_line",
            "fixed_asset_disposal", "fixed_asset_import_batch", "fixed_asset_import_row");

    @Autowired
    private LedgerBackupService backups;

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private DocumentService documents;

    @Autowired
    private VoucherService vouchers;

    @Autowired
    private IdentityService identities;

    @Autowired
    private AccountingExperienceService experiences;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.example.accounting.ledger.internal.port.ReportFormulaRepository formulas;

    @Autowired
    private com.example.accounting.ledger.internal.application.CashFlowTemplateProvisioner cashFlowProvisioner;

    @Test
    void backsUpAndRestoresBusinessDataAndAttachmentsIntoANewLedger() {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("源账套")).id();
        CurrentUserResolver.ResolvedUser agent = new CurrentUserResolver.ResolvedUser(
                UUID.randomUUID(), "test", UUID.randomUUID().toString(), "Agent", null, UserType.AGENT);
        identities.ensureUser(agent);
        ledgers.addMember(owner.id(), sourceId, new LedgerRequests.AddMember(agent.id(), LedgerRole.AGENT));
        experiences.create(agent.id(), new ExperienceRequests.Create(
                ExperienceScope.LEDGER, sourceId, "账套经验", "差旅费进入管理费用", List.of("差旅")));
        byte[] attachment = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        documents.upload(owner.id(), sourceId, "receipt.png", "image/png", attachment.length,
                new ByteArrayInputStream(attachment));
        vouchers.create(owner.id(), sourceId, new VoucherRequests.Create(
                ledgers.periodId(sourceId, "2026-01"), LocalDate.of(2026, 1, 15),
                "GENERAL", "1", "backup test", List.of(
                new VoucherRequests.Line(ledgers.accountId(sourceId, "1001"), "DEBIT", "CNY",
                        new BigDecimal("100"), BigDecimal.ONE, "debit",
                        cashItem(sourceId), null, null, null),
                new VoucherRequests.Line(ledgers.accountId(sourceId, "3001"), "CREDIT", "CNY",
                        new BigDecimal("100"), BigDecimal.ONE, "credit"))));
        jdbc.update("""
                insert into agent_tool_audit
                    (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash,
                     outcome, error_code, duration_ms)
                values (?, 'get_ledger_context', ?, ?, 'backup-trace', 'input', null,
                        'SUCCESS', null, 19)
                """, UUID.randomUUID(), sourceId, owner.id());

        byte[] archive = backups.backup(owner.id(), sourceId);
        LedgerResponses.Ledger restored = backups.restore(
                owner, null, archive.length, new ByteArrayInputStream(archive));

        assertThat(restored.id()).isNotEqualTo(sourceId);
        assertThat(restored.name()).isEqualTo("源账套（恢复）");
        assertThat(ledgers.role(owner.id(), restored.id())).isEqualTo(LedgerRole.OWNER);
        for (String table : BUSINESS_TABLES) {
            assertThat(count(table, restored.id()))
                    .as("restored row count for %s", table)
                    .isEqualTo(count(table, sourceId));
        }
        List<UUID> sourceAccounts = ids("ledger_account", sourceId);
        List<UUID> restoredAccounts = ids("ledger_account", restored.id());
        assertThat(restoredAccounts).doesNotContainAnyElementsOf(sourceAccounts);
        assertThat(jdbc.queryForObject("""
                select count(*) from ledger_account
                where ledger_id = ? and standard_account_key is null
                """, Integer.class, restored.id())).isZero();
        assertThat(jdbc.queryForObject("select title from accounting_experience where ledger_id = ?",
                String.class, restored.id())).isEqualTo("账套经验");
        assertThat(jdbc.queryForMap("""
                select result_hash, duration_ms from agent_tool_audit
                where ledger_id = ? and trace_id = 'backup-trace'
                """, restored.id())).containsEntry("result_hash", null)
                .containsEntry("duration_ms", 19L);

        UUID restoredDocumentId = ids("document", restored.id()).getFirst();
        assertThat(documents.content(owner.id(), restored.id(), restoredDocumentId).bytes())
                .containsExactly(attachment);
    }

    @Test
    void onlyAnOwnerCanDownloadABackup() {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        CurrentUserResolver.ResolvedUser viewer = user(UUID.randomUUID());
        UUID ledgerId = ledgers.create(owner, createRequest("权限测试")).id();
        identities.ensureUser(viewer);
        ledgers.addMember(owner.id(), ledgerId, new LedgerRequests.AddMember(viewer.id(), LedgerRole.VIEWER));

        assertThatThrownBy(() -> backups.backup(viewer.id(), ledgerId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("INSUFFICIENT_LEDGER_ROLE"));
    }

    @Test
    void restoresVersionOneBackupsWithoutExperienceTable() throws Exception {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("旧格式账套")).id();
        jdbc.update("""
                insert into agent_tool_audit
                    (id, tool_name, ledger_id, actor_id, trace_id, input_hash, result_hash,
                     outcome, error_code, duration_ms)
                values (?, 'get_ledger', ?, ?, 'legacy-audit', 'input', 'legacy-result',
                        'SUCCESS', null, 23)
                """, UUID.randomUUID(), sourceId, owner.id());
        byte[] versionOne = downgradeToVersionOne(backups.backup(owner.id(), sourceId));

        LedgerResponses.Ledger restored = backups.restore(
                owner, null, versionOne.length, new ByteArrayInputStream(versionOne));

        assertThat(restored.id()).isNotEqualTo(sourceId);
        assertThat(count("accounting_experience", restored.id())).isZero();
        assertThat(jdbc.queryForMap("""
                select result_hash, duration_ms from agent_tool_audit
                where ledger_id = ? and trace_id = 'legacy-audit'
                """, restored.id())).containsEntry("result_hash", "legacy-result")
                .containsEntry("duration_ms", 0L);
    }

    @Test
    void restoresVersionTwoBackupsAndBackfillsDimensionPointers() throws Exception {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("V2账套")).id();
        UUID periodId = ledgers.periodId(sourceId, "2026-01");
        ledgers.replaceOpeningBalances(owner.id(), sourceId, List.of(
                new LedgerRequests.OpeningBalanceLine(
                        ledgers.accountId(sourceId, "1001"), periodId, "CNY", "",
                        new BigDecimal("10"), BigDecimal.ZERO, BigDecimal.ONE),
                new LedgerRequests.OpeningBalanceLine(
                        ledgers.accountId(sourceId, "3001"), periodId, "CNY", "",
                        BigDecimal.ZERO, new BigDecimal("10"), BigDecimal.ONE)));
        vouchers.create(owner.id(), sourceId, new VoucherRequests.Create(
                ledgers.periodId(sourceId, "2026-01"), LocalDate.of(2026, 1, 15),
                "GENERAL", "V2", "legacy dimensions", List.of(
                new VoucherRequests.Line(ledgers.accountId(sourceId, "1001"), "DEBIT", "CNY",
                        new BigDecimal("10"), BigDecimal.ONE, "debit",
                        cashItem(sourceId), null, null, null),
                new VoucherRequests.Line(ledgers.accountId(sourceId, "3001"), "CREDIT", "CNY",
                        new BigDecimal("10"), BigDecimal.ONE, "credit"))));
        byte[] versionTwo = downgrade(backups.backup(owner.id(), sourceId), 2);

        LedgerResponses.Ledger restored = backups.restore(
                owner, null, versionTwo.length, new ByteArrayInputStream(versionTwo));

        assertThat(jdbc.queryForObject("""
                select count(*) from voucher_line
                where ledger_id = ? and dimension_combination_id is null
                """, Integer.class, restored.id())).isZero();
        assertThat(count("dimension_combination", restored.id())).isGreaterThan(0);
        assertThat(jdbc.queryForObject("""
                select count(*) from opening_balance balance
                join dimension_combination combination
                  on combination.ledger_id = balance.ledger_id
                 and combination.id = balance.dimension_combination_id
                where balance.ledger_id = ?
                  and combination.kind = 'STRUCTURED' and combination.canonical_key = 'v1;'
                """, Integer.class, restored.id())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                select count(*) from dimension_combination
                where ledger_id = ? and canonical_key = 'legacy-v1;' || md5('v1;')
                """, Integer.class, restored.id())).isZero();
    }

    @Test
    void restoresVersionThreeStructuredCombinationIdentityUsingRemappedMembers() throws Exception {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("V3 structured dimensions")).id();
        LedgerResponses.DimensionType sourceType = ledgers.listDimensionTypes(owner.id(), sourceId).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue sourceValue = ledgers.createDimensionValue(
                owner.id(), sourceId, sourceType.id(),
                new LedgerRequests.DimensionValueCreate("RESTORE-C001", "Restore customer"));
        LedgerResponses.Account sourceReceivable = ledgers.createAccount(owner.id(), sourceId,
                new LedgerRequests.AccountCreate(
                        "1197", "Restore receivable", "ASSET.ACCOUNTS_RECEIVABLE", "CURRENT_ASSET", "DEBIT",
                        null, false, null, false, null,
                        List.of(new LedgerRequests.DimensionRequirement(sourceType.id(), true))));
        UUID sourcePeriodId = ledgers.periodId(sourceId, "2026-01");
        ledgers.replaceOpeningBalances(owner.id(), sourceId, List.of(
                new LedgerRequests.OpeningBalanceLine(
                        sourceReceivable.id(), sourcePeriodId, "CNY", null,
                        new BigDecimal("25.00"), BigDecimal.ZERO, BigDecimal.ONE,
                        List.of(new LedgerRequests.OpeningBalanceDimension(
                                sourceType.id(), sourceValue.id()))),
                new LedgerRequests.OpeningBalanceLine(
                        ledgers.accountId(sourceId, "3001"), sourcePeriodId, "CNY", "",
                        BigDecimal.ZERO, new BigDecimal("25.00"), BigDecimal.ONE)));

        byte[] archive = downgrade(backups.backup(owner.id(), sourceId), 3);
        LedgerResponses.Ledger restored = backups.restore(
                owner, null, archive.length, new ByteArrayInputStream(archive));

        assertThat(jdbc.queryForObject("""
                select count(*) from report_formula_snapshot
                where ledger_id = ? and schema_version = 1 and formula_kind <> 'LEGACY'
                """, Integer.class, restored.id())).isEqualTo(3);
        assertThat(count("report_formula_revision", restored.id())).isEqualTo(2);

        LedgerResponses.DimensionType restoredType = ledgers.listDimensionTypes(owner.id(), restored.id()).stream()
                .filter(type -> type.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue restoredValue = ledgers
                .listDimensionValues(owner.id(), restored.id(), restoredType.id()).stream()
                .filter(value -> value.code().equals("RESTORE-C001")).findFirst().orElseThrow();
        UUID restoredReceivableId = ledgers.accountId(restored.id(), "1197");
        UUID restoredPeriodId = ledgers.periodId(restored.id(), "2026-01");
        String expectedCanonical = "v1;" + restoredType.id() + "=" + restoredValue.id() + ";";
        Map<String, Object> restoredCombination = jdbc.queryForMap("""
                select combination.id, combination.canonical_key, combination.dimension_key
                from dimension_combination combination
                join dimension_combination_member member
                  on member.ledger_id = combination.ledger_id
                 and member.combination_id = combination.id
                where combination.ledger_id = ?
                  and member.dimension_type_id = ? and member.dimension_value_id = ?
                """, restored.id(), restoredType.id(), restoredValue.id());
        UUID restoredCombinationId = (UUID) restoredCombination.get("id");
        assertThat(restoredCombination.get("canonical_key")).isEqualTo(expectedCanonical);
        assertThat(restoredCombination.get("dimension_key"))
                .isEqualTo(jdbc.queryForObject("select md5(?)", String.class, expectedCanonical));
        assertThat(restoredCombination.get("canonical_key").toString())
                .doesNotContain(sourceType.id().toString(), sourceValue.id().toString());
        assertThat(jdbc.queryForObject("""
                select count(*)
                from opening_balance balance
                join opening_balance_dimension relation
                  on relation.ledger_id = balance.ledger_id
                 and relation.opening_balance_id = balance.id
                where balance.ledger_id = ? and balance.account_id = ?
                  and balance.dimension_combination_id = ?
                  and relation.dimension_type_id = ? and relation.dimension_value_id = ?
                """, Integer.class, restored.id(), restoredReceivableId, restoredCombinationId,
                restoredType.id(), restoredValue.id())).isEqualTo(1);

        int combinationCount = count("dimension_combination", restored.id());
        ledgers.replaceOpeningBalances(owner.id(), restored.id(), List.of(
                new LedgerRequests.OpeningBalanceLine(
                        restoredReceivableId, restoredPeriodId, "CNY", null,
                        new BigDecimal("30.00"), BigDecimal.ZERO, BigDecimal.ONE,
                        List.of(new LedgerRequests.OpeningBalanceDimension(
                                restoredType.id(), restoredValue.id()))),
                new LedgerRequests.OpeningBalanceLine(
                        ledgers.accountId(restored.id(), "3001"), restoredPeriodId, "CNY", "",
                        BigDecimal.ZERO, new BigDecimal("30.00"), BigDecimal.ONE)));

        assertThat(count("dimension_combination", restored.id())).isEqualTo(combinationCount);
        assertThat(jdbc.queryForObject("""
                select dimension_combination_id from opening_balance
                where ledger_id = ? and account_id = ?
                """, UUID.class, restored.id(), restoredReceivableId)).isEqualTo(restoredCombinationId);
    }

    @Test
    void rejectsZipEntriesThatEscapeTheArchiveRoot() throws Exception {
        byte[] archive;
        try (ByteArrayOutputStream output = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry("../manifest.json"));
            zip.write("{}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
            zip.finish();
            archive = output.toByteArray();
        }

        CurrentUserResolver.ResolvedUser actor = user(UUID.randomUUID());
        assertThatThrownBy(() -> backups.restore(
                actor, null, archive.length, new ByteArrayInputStream(archive)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo("LEDGER_BACKUP_INVALID"));
    }

    @Test
    void legacyBackupRestoreProvisionsDetailedCashFlowTemplate() throws Exception {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("pre-cash-flow")).id();
        // 把源账套回退成功能上线前的形态：三个粗分类项目、无 CASH_FLOW 公式。
        jdbc.update("""
                delete from report_formula_revision
                where formula_id in (
                    select id from report_formula_snapshot where ledger_id = ? and code = 'CASH_FLOW')
                """, sourceId);
        jdbc.update("delete from report_formula_snapshot where ledger_id = ? and code = 'CASH_FLOW'", sourceId);
        jdbc.update("delete from cash_flow_item where ledger_id = ?", sourceId);
        jdbc.update("""
                insert into cash_flow_item (id, ledger_id, code, name, is_template)
                values (?, ?, 'OPERATING', '经营活动产生的现金流量', true),
                       (?, ?, 'INVESTING', '投资活动产生的现金流量', true),
                       (?, ?, 'FINANCING', '筹资活动产生的现金流量', true)
                """, UUID.randomUUID(), sourceId, UUID.randomUUID(), sourceId, UUID.randomUUID(), sourceId);

        byte[] archive = downgrade(backups.backup(owner.id(), sourceId), 4);
        LedgerResponses.Ledger restored = backups.restore(
                owner, null, archive.length, new ByteArrayInputStream(archive));

        assertThat(jdbc.queryForObject("""
                select count(*) from cash_flow_item
                where ledger_id = ? and status = 'ACTIVE'
                """, Integer.class, restored.id())).isEqualTo(16);
        assertThat(jdbc.queryForObject("""
                select count(*) from cash_flow_item
                where ledger_id = ? and status = 'INACTIVE' and code in ('OPERATING','INVESTING','FINANCING')
                """, Integer.class, restored.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                select count(*) from report_formula_snapshot
                where ledger_id = ? and code = 'CASH_FLOW' and schema_version = 1 and published_version = 1
                """, Integer.class, restored.id())).isEqualTo(1);
        assertThat(formulas.findPublishedVersion(restored.id(), "CASH_FLOW", 1)).isPresent();
        // 幂等：再次补齐不产生额外行或版本。
        cashFlowProvisioner.provision(restored.id());
        assertThat(jdbc.queryForObject("""
                select count(*) from cash_flow_item where ledger_id = ? and status = 'ACTIVE'
                """, Integer.class, restored.id())).isEqualTo(16);
    }

    private int count(String table, UUID ledgerId) {
        if ("report_formula_revision".equals(table)) {
            // The revision table carries no ledger_id; reach it through the snapshot.
            return jdbc.queryForObject("""
                    select count(*) from report_formula_revision revision
                    join report_formula_snapshot snapshot on snapshot.id = revision.formula_id
                    where snapshot.ledger_id = ?
                    """, Integer.class, ledgerId);
        }
        return jdbc.queryForObject("select count(*) from " + table + " where ledger_id = ?",
                Integer.class, ledgerId);
    }

    private List<UUID> ids(String table, UUID ledgerId) {
        return jdbc.queryForList("select id from " + table + " where ledger_id = ? order by id",
                UUID.class, ledgerId);
    }

    private byte[] downgradeToVersionOne(byte[] archive) throws Exception {
        return downgrade(archive, 1);
    }

    private byte[] downgrade(byte[] archive, int version) throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        ObjectNode data = (ObjectNode) mapper.readTree(entries.get("data.json"));
        ObjectNode tables = (ObjectNode) data.path("tables");
        if (version == 1) {
            tables.remove("accounting_experience");
        }
        if (version <= 2) {
            Map<String, String> combinationCanonicalKeys = new LinkedHashMap<>();
            tables.path("dimension_combination").forEach(row -> combinationCanonicalKeys.put(
                    row.path("id").asText(), row.path("canonical_key").asText()));
            tables.path("opening_balance").forEach(row -> {
                String canonical = combinationCanonicalKeys.get(row.path("dimension_combination_id").asText());
                if ("v1;".equals(canonical)) {
                    ((ObjectNode) row).put("dimension_key", "");
                } else if (canonical != null && canonical.startsWith("legacy-v1;")) {
                    ((ObjectNode) row).put("dimension_key", canonical.substring("legacy-v1;".length()));
                }
            });
            tables.remove("dimension_combination");
            tables.remove("dimension_combination_member");
            tables.remove("opening_balance_dimension");
            tables.path("opening_balance").forEach(
                    row -> ((ObjectNode) row).remove("dimension_combination_id"));
            tables.path("voucher_line").forEach(
                    row -> ((ObjectNode) row).remove("dimension_combination_id"));
        }
        if (version <= 3) {
            tables.remove("report_formula_revision");
            tables.remove("report_formula_account_reference");
        }
        if (version == 1) {
            data.path("tables").path("agent_tool_audit").forEach(
                    row -> ((ObjectNode) row).remove("duration_ms"));
        }
        byte[] dataBytes = mapper.writeValueAsBytes(data);
        ObjectNode manifest = (ObjectNode) mapper.readTree(entries.get("manifest.json"));
        manifest.put("version", version);
        manifest.put("dataSha256", HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(dataBytes)));
        entries.put("data.json", dataBytes);
        entries.put("manifest.json", mapper.writeValueAsBytes(manifest));

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
            zip.finish();
        }
        return output.toByteArray();
    }

    private LedgerRequests.Create createRequest(String name) {
        return new LedgerRequests.Create(name, "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false);
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private UUID cashItem(UUID ledgerId) {
        return jdbc.queryForObject(
                "select id from cash_flow_item where ledger_id = ? and code = 'SME_CF_01_SALES_RECEIPTS'",
                UUID.class, ledgerId);
    }
}
