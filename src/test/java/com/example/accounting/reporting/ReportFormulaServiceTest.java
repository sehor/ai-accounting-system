package com.example.accounting.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "accounting.balance.worker-enabled=false")
class ReportFormulaServiceTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private ReportFormulaService service;

    @Autowired
    private ReportingService reporting;

    @Autowired
    private com.example.accounting.voucher.VoucherService vouchers;

    @Autowired
    private com.example.accounting.identity.IdentityService identities;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private BalanceProjectionRepository projection;

    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();

    @Test
    void completeSmeLifecycleCreateSavePreviewPublishAndReport() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));
        applyProjection(ledgerId);

        ReportFormulaResponses.Workspace workspace = service.workspace(userId, ledgerId, "BALANCE_SHEET");
        assertThat(workspace.publishedVersion()).isEqualTo(1);
        assertThat(workspace.kind()).isEqualTo("FIXED_LINES");
        assertThat(workspace.draft()).isNull();

        service.createDraft(userId, ledgerId, "BALANCE_SHEET");
        ReportFormulaResponses.Draft draft = service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(1L,
                        List.of(new ReportFormulaRequests.LineEdit(
                                "bs-1", "货币资金（改）", expression("ACCOUNT_BALANCE", "DEBIT",
                                        List.of(json("STANDARD_ACCOUNT_KEY", "ASSET.CASH"),
                                                json("STANDARD_ACCOUNT_KEY", "ASSET.BANK_DEPOSIT"))))),
                        null));
        assertThat(draft.version()).isEqualTo(2);
        assertThat(mapper.valueToTree(draft.definition()).path("groups").path(0).path("lines").path(1).path("name").asText())
                .isEqualTo("货币资金（改）");

        ReportFormulaResponses.PreviewResult preview = service.preview(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PreviewRequest(2L, "2026-01", null, null));
        assertThat(preview.blockingIssues()).isEmpty();
        assertThat(preview.previewedDraftVersion()).isEqualTo(2);
        assertThat(preview.statement()).isNotNull();

        ReportFormulaResponses.PublishResult publish = service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 2L, false));
        assertThat(publish.publishedVersion()).isEqualTo(2);

        StatutoryReportResponses.Statement report = reporting.statutoryStatement(
                userId, ledgerId, "balance-sheet", "2026-01");
        assertThat(report.formulaVersion()).isEqualTo(2);
        assertThat(report.groups().get(0).lines().get(1).name()).isEqualTo("货币资金（改）");

        ReportFormulaResponses.VersionPage page = service.versions(userId, ledgerId, "BALANCE_SHEET", 1, 20);
        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.items()).extracting(ReportFormulaResponses.VersionInfo::version)
                .containsExactly(2, 1);
        assertThat(page.items().get(0).source()).isEqualTo("USER");
        assertThat(service.version(userId, ledgerId, "BALANCE_SHEET", 1).version()).isEqualTo(1);
        assertThat(auditActions(ledgerId)).contains("SAVE", "PUBLISH");
    }

    @Test
    void staleVersionsConflictAndPreviewIsRequiredBeforePublish() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1");
        service.createDraft(userId, ledgerId, "BALANCE_SHEET");

        assertThatThrownBy(() -> service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(99L, null, null)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_FORMULA_VERSION_CONFLICT"));
        assertThatThrownBy(() -> service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 1L, false)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_FORMULA_PREVIEW_REQUIRED"));
    }

    @Test
    void unbalancedPreviewRequiresWarningAcknowledgement() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "100"), line(ledgerId, "3001", "CREDIT", "100")));
        applyProjection(ledgerId);
        service.createDraft(userId, ledgerId, "BALANCE_SHEET");
        // Zero out the assets total line: the asset equation then fails.
        service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(1L,
                        List.of(new ReportFormulaRequests.LineEdit(
                                "bs-30", "资产总计", combination(List.of()))), null));
        ReportFormulaResponses.PreviewResult preview = service.preview(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PreviewRequest(2L, "2026-01", null, null));
        assertThat(preview.previewHasWarnings()).isTrue();
        assertThat(preview.warnings()).extracting(ReportFormulaResponses.Warning::code)
                .contains("ASSET_EQUATION");

        assertThatThrownBy(() -> service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 2L, false)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_FORMULA_WARNING_ACK_REQUIRED"));
        ReportFormulaResponses.PublishResult publish = service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 2L, true));
        assertThat(publish.publishedVersion()).isEqualTo(2);
    }

    @Test
    void discardResetAndRollbackFollowTheStateMachine() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "SME", "v1");

        service.createDraft(userId, ledgerId, "BALANCE_SHEET");
        service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(1L,
                        List.of(new ReportFormulaRequests.LineEdit(
                                "bs-1", "改名", expression("ACCOUNT_BALANCE", "DEBIT",
                                        List.of(json("STANDARD_ACCOUNT_KEY", "ASSET.CASH"))))), null));

        ReportFormulaResponses.Draft reset = service.resetDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftReset(2L));
        assertThat(reset.version()).isEqualTo(3);
        assertThat(reset.lastPreviewedDraftVersion()).isNull();
        assertThat(mapper.valueToTree(reset.definition()).path("groups").path(0).path("lines").path(1).path("name").asText())
                .isNotEqualTo("改名");

        service.deleteDraft(userId, ledgerId, "BALANCE_SHEET");
        assertThatThrownBy(() -> service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 3L, false)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_FORMULA_DRAFT_NOT_FOUND"));
        assertThat(auditActions(ledgerId)).contains("SAVE", "RESET", "DISCARD");

        // Rollback requires no draft; v1 -> new published v2 with source ROLLBACK.
        ReportFormulaResponses.RollbackResult rollback = service.rollback(userId, ledgerId,
                "BALANCE_SHEET", 1, new ReportFormulaRequests.RollbackRequest(1));
        assertThat(rollback.publishedVersion()).isEqualTo(2);
        ReportFormulaResponses.VersionInfo v2 = service.version(userId, ledgerId, "BALANCE_SHEET", 2);
        assertThat(v2.source()).isEqualTo("ROLLBACK");
        assertThat(v2.rollbackOfVersion()).isEqualTo(1);
        assertThat(auditActions(ledgerId)).contains("ROLLBACK");

        assertThatThrownBy(() -> service.rollback(userId, ledgerId, "BALANCE_SHEET", 1,
                new ReportFormulaRequests.RollbackRequest(1)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("REPORT_FORMULA_VERSION_CONFLICT"));
    }

    @Test
    void casRulesCanBeEditedPublishedAndReported() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = createLedger(userId, "CAS", "2006-18");
        postVoucher(userId, ledgerId, "2026-01", "1", List.of(
                line(ledgerId, "1001", "DEBIT", "70"), line(ledgerId, "4001", "CREDIT", "70")));
        applyProjection(ledgerId);

        service.createDraft(userId, ledgerId, "BALANCE_SHEET");
        service.updateDraft(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.DraftUpdate(1L, null, List.of(
                        new ReportFormulaRequests.RuleEdit("DEBIT_CATEGORIES", "DEBIT",
                                List.of("CURRENT_ASSET"), List.of()),
                        new ReportFormulaRequests.RuleEdit("CREDIT_CATEGORIES", "CREDIT",
                                List.of("EQUITY"), List.of()))));
        ReportFormulaResponses.PreviewResult preview = service.preview(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PreviewRequest(2L, null, "2026-01", "2026-01"));
        assertThat(preview.blockingIssues()).isEmpty();
        assertThat(mapper.valueToTree(preview.statement()).path("totalLines").asInt()).isEqualTo(2);

        service.publish(userId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 2L, false));
        ReportResponses.Statement report = reporting.balanceSheet(userId, ledgerId, "2026-01");
        assertThat(report.formulaVersion()).isEqualTo(2);
        assertThat(report.lines()).extracting(ReportResponses.StatementLine::code)
                .containsExactly("1001", "4001");
    }

    @Test
    void viewersReadAndOnlyOwnersAndEditorsWrite() {
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID ledgerId = createLedger(ownerId, "SME", "v1");
        identities.ensureUser(new CurrentUserResolver.ResolvedUser(viewerId, "viewer", viewerId.toString()));
        ledgers.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(viewerId, LedgerRole.VIEWER));

        assertThat(service.workspace(viewerId, ledgerId, "BALANCE_SHEET")).isNotNull();
        assertThatThrownBy(() -> service.createDraft(viewerId, ledgerId, "BALANCE_SHEET"))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_LEDGER_ROLE"));
        assertThatThrownBy(() -> service.publish(viewerId, ledgerId, "BALANCE_SHEET",
                new ReportFormulaRequests.PublishRequest(1, 1L, false)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_LEDGER_ROLE"));
    }

    private List<String> auditActions(UUID ledgerId) {
        return jdbc.queryForList("""
                select action from audit_revision
                where ledger_id = ? and aggregate_type = 'REPORT_FORMULA' order by created_at, id
                """, String.class, ledgerId);
    }

    private JsonNode expression(String operation, String side, List<ObjectNode> accounts) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "ACCOUNT_AMOUNT");
        node.put("operation", operation);
        node.put("side", side);
        ArrayNode accountArray = node.putArray("accounts");
        accounts.forEach(accountArray::add);
        return node;
    }

    private JsonNode combination(List<JsonNode> components) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", "LINEAR_COMBINATION");
        ArrayNode array = node.putArray("components");
        components.forEach(array::add);
        return node;
    }

    private ObjectNode json(String type, String value) {
        ObjectNode node = mapper.createObjectNode();
        node.put("type", type);
        node.put("value", value);
        return node;
    }

    private void postVoucher(UUID userId, UUID ledgerId, String periodCode, String number,
                             List<com.example.accounting.voucher.VoucherRequests.Line> lines) {
        UUID periodId = jdbc.queryForObject(
                "select id from accounting_period where ledger_id = ? and period_code = ?",
                UUID.class, ledgerId, periodCode);
        vouchers.create(userId, ledgerId, new com.example.accounting.voucher.VoucherRequests.Create(
                periodId, LocalDate.of(2026, 1, 15), "GENERAL", number, "formula test", lines));
    }

    private com.example.accounting.voucher.VoucherRequests.Line line(
            UUID ledgerId, String code, String side, String amount) {
        UUID accountId = jdbc.queryForObject(
                "select id from ledger_account where ledger_id = ? and code = ?",
                UUID.class, ledgerId, code);
        return new com.example.accounting.voucher.VoucherRequests.Line(accountId, side, "CNY",
                new BigDecimal(amount), BigDecimal.ONE, "test");
    }

    private UUID createLedger(UUID userId, String standardCode, String standardVersion) {
        CurrentUserResolver.ResolvedUser user =
                new CurrentUserResolver.ResolvedUser(userId, "test", UUID.randomUUID().toString());
        return ledgers.create(user, new LedgerRequests.Create("公式生命周期 " + standardCode,
                standardCode, standardVersion, "CNY", LocalDate.of(2026, 1, 1), false)).id();
    }

    private void applyProjection(UUID ledgerId) {
        for (int attempt = 0; attempt < 50 && !projection.status(ledgerId, "2026-01").fresh(); attempt++) {
            if (!projection.applyPendingBatch(200, 5000)) {
                Thread.onSpinWait();
            }
        }
        assertThat(projection.status(ledgerId, "2026-01").fresh()).isTrue();
    }
}
