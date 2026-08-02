package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.documents.DocumentService;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
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
            "cash_flow_item", "dimension_type", "dimension_value", "ledger_account",
            "ledger_account_dimension", "accounting_period", "opening_balance", "voucher",
            "voucher_line", "voucher_line_dimension", "voucher_approval", "period_action_audit",
            "report_formula_snapshot", "audit_revision", "document", "document_extraction",
            "agent_tool_audit");

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
    private JdbcTemplate jdbc;

    @Test
    void backsUpAndRestoresBusinessDataAndAttachmentsIntoANewLedger() {
        CurrentUserResolver.ResolvedUser owner = user(UUID.randomUUID());
        UUID sourceId = ledgers.create(owner, createRequest("源账套")).id();
        byte[] attachment = new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10};
        documents.upload(owner.id(), sourceId, "receipt.png", "image/png", attachment.length,
                new ByteArrayInputStream(attachment));
        vouchers.create(owner.id(), sourceId, new VoucherRequests.Create(
                ledgers.periodId(sourceId, "2026-01"), LocalDate.of(2026, 1, 15),
                "GENERAL", "1", "backup test", List.of(
                new VoucherRequests.Line(ledgers.accountId(sourceId, "1001"), "DEBIT", "CNY",
                        new BigDecimal("100"), BigDecimal.ONE, "debit"),
                new VoucherRequests.Line(ledgers.accountId(sourceId, "3001"), "CREDIT", "CNY",
                        new BigDecimal("100"), BigDecimal.ONE, "credit"))));

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

    private int count(String table, UUID ledgerId) {
        return jdbc.queryForObject("select count(*) from " + table + " where ledger_id = ?",
                Integer.class, ledgerId);
    }

    private List<UUID> ids(String table, UUID ledgerId) {
        return jdbc.queryForList("select id from " + table + " where ledger_id = ? order by id",
                UUID.class, ledgerId);
    }

    private LedgerRequests.Create createRequest(String name) {
        return new LedgerRequests.Create(name, "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false);
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }
}
