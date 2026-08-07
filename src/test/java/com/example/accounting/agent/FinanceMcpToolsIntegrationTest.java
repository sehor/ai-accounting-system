package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.fixedasset.FixedAssetRequests;
import com.example.accounting.fixedasset.FixedAssetService;
import com.example.accounting.ledger.AccountExchangeService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.audit.AuditContext;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
@org.junit.jupiter.api.Disabled("Creates ledgers; disabled until tests use an isolated database")
class FinanceMcpToolsIntegrationTest {

    @Autowired
    private FinanceMcpTools tools;

    @Autowired
    private IdentityService identityService;

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private VoucherService voucherService;

    @Autowired
    private FixedAssetService fixedAssetService;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AuditContext.clear();
    }

    @Test
    void agentCanSaveAPostedVoucherThroughMcpTools() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        identityService.ensureUser(user(agentId));
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-draft", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        ledgerService.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.AGENT));
        VoucherRequests.Create request = request(ledgerId);

        assertThatThrownBy(() -> voucherService.create(agentId, ledgerId, request))
                .isInstanceOf(ApiProblemException.class);

        authenticate(agentId);
        String traceId = "mcp-" + UUID.randomUUID();
        AuditContext.setTraceId(traceId);
        var draft = tools.createVoucherDraft(request, ledgerId, "draft-key");
        var retriedDraft = tools.createVoucherDraft(request, ledgerId, "draft-key");
        String content = Base64.getEncoder().encodeToString("invoice".getBytes(StandardCharsets.UTF_8));
        var document = tools.uploadDocument(ledgerId, "invoice.pdf", "application/pdf", content, "upload-key");
        var retriedDocument = tools.uploadDocument(
                ledgerId, "invoice.pdf", "application/pdf", content, "upload-key");

        assertThat(draft.status()).isEqualTo("POSTED");
        assertThat(retriedDraft.id()).isEqualTo(draft.id());
        assertThat(retriedDocument.id()).isEqualTo(document.id());
        assertThatThrownBy(() -> voucherService.post(agentId, ledgerId, draft.id()))
                .isInstanceOf(ApiProblemException.class);
        await(() -> auditCount(traceId, "SUCCESS") == 4L);
        assertThat(jdbc.queryForMap("""
                select result_hash, duration_ms from agent_tool_audit
                where trace_id = ? order by created_at desc limit 1
                """, traceId)).containsEntry("result_hash", null)
                .satisfies(row -> assertThat(((Number) row.get("duration_ms")).longValue()).isNotNegative());
    }

    @Test
    void failedToolCallKeepsRequestTraceAndErrorCodeInAudit() {
        UUID ownerId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-audit", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        authenticate(ownerId);
        String traceId = "mcp-" + UUID.randomUUID();
        AuditContext.setTraceId(traceId);

        assertThatThrownBy(() -> tools.financeQuery(ledgerId, "not_allowed", null))
                .isInstanceOf(ApiProblemException.class);

        await(() -> auditCount(traceId, "FAILURE") == 1L);
        Map<String, Object> audit = jdbc.queryForMap("""
                select trace_id, outcome, error_code from agent_tool_audit
                where trace_id = ? order by created_at desc limit 1
                """, traceId);
        assertThat(audit).containsEntry("trace_id", traceId)
                .containsEntry("outcome", "FAILURE")
                .containsEntry("error_code", "FINANCE_QUERY_INVALID");
    }

    @Test
    void agentSaveAutomaticallyApprovesAndPosts() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        identityService.ensureUser(user(agentId));
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-approval", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), true)).id();
        ledgerService.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.AGENT));
        authenticate(agentId);

        var voucher = tools.createVoucherDraft(request(ledgerId), ledgerId, "approval-draft");

        assertThat(voucher.status()).isEqualTo("POSTED");
        assertThat(jdbc.queryForObject(
                "select count(*) from voucher_approval where voucher_id = ? and action = 'APPROVE'",
                Long.class, voucher.id())).isEqualTo(1L);
    }

    @Test
    void agentCanIdempotentlyCreateAccountsAndListPeriods() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        identityService.ensureUser(user(agentId));
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-accounts", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        ledgerService.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.AGENT));
        authenticate(agentId);
        var request = new LedgerRequests.AccountCreate(
                "100201", "银行存款-建设银行", "ASSET", "DEBIT");

        var account = tools.ensureAccount(ledgerId, request);
        var retried = tools.ensureAccount(ledgerId, request);
        var exactMatches = tools.searchAccounts(
                ledgerId, "100201", LedgerRequests.AccountMatchMode.EXACT, 5);
        var fuzzyMatches = tools.searchAccounts(
                ledgerId, "1002", LedgerRequests.AccountMatchMode.FUZZY, 5);

        assertThat(retried.id()).isEqualTo(account.id());
        assertThat(exactMatches).singleElement().satisfies(match -> {
            assertThat(match.account().id()).isEqualTo(account.id());
            assertThat(match.parent()).isNotNull();
            assertThat(match.parent().code()).isEqualTo("1002");
            assertThat(match.children()).isEmpty();
        });
        assertThat(fuzzyMatches).extracting(match -> match.account().code())
                .contains("1002", "100201");
        assertThat(tools.listPeriods(ledgerId))
                .anyMatch(period -> period.periodCode().equals("2026-06"));
        assertThatThrownBy(() -> tools.ensureAccount(ledgerId, new LedgerRequests.AccountCreate(
                "100201", "冲突科目", "ASSET", "DEBIT")))
                .isInstanceOf(ApiProblemException.class);
    }

    @Test
    void aggregateContextsMatchTheExistingFineGrainedTools() {
        UUID ownerId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-context", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        authenticate(ownerId);

        var operator = tools.getOperatorContext();
        var ledger = tools.getLedgerContext(ledgerId);

        assertThat(operator).isEqualTo(AgentContextResponses.toolCatalog());
        assertThat(ledger.ledger()).isEqualTo(tools.getLedger(ledgerId));
        assertThat(ledger.role()).isEqualTo(LedgerRole.OWNER);
        assertThat(ledger.periods()).isEqualTo(tools.listPeriods(ledgerId));
        assertThat(ledger.accounts()).isEqualTo(tools.listAccounts(ledgerId));
        assertThat(ledger.dimensionTypes()).isEqualTo(tools.listDimensionTypes(ledgerId));
        assertThat(ledger.cashFlowItems()).isEqualTo(tools.listCashFlowItems(ledgerId));
    }

    @Test
    void agentCanExportAccountsVouchersAndReportsThroughMcpTools() {
        UUID ownerId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-exports", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        authenticate(ownerId);

        var accounts = tools.exportAccounts(ledgerId, AccountExchangeService.Format.STANDARD);
        var vouchers = tools.exportKingdeeVouchers(ledgerId);
        var report = tools.exportReport(ledgerId, "trial_balance", "2026-01", false);

        assertThat(accounts.fileName()).isEqualTo("accounts-standard.xlsx");
        assertThat(accounts.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(Base64.getDecoder().decode(accounts.base64Content())).startsWith((byte) 'P', (byte) 'K');
        assertThat(vouchers.fileName()).isEqualTo("kingdee-vouchers.xlsx");
        assertThat(Base64.getDecoder().decode(vouchers.base64Content())).startsWith((byte) 'P', (byte) 'K');
        assertThat(report.fileName()).isEqualTo("trial-balance-2026-01.json");
        assertThat(report.contentType()).isEqualTo("application/json; charset=UTF-8");
        assertThat(new String(Base64.getDecoder().decode(report.base64Content()), StandardCharsets.UTF_8))
                .contains("trial_balance", ledgerId.toString());

        for (String reportName : List.of("balance_sheet", "income_statement", "general_ledger", "sub_ledger")) {
            var exported = tools.exportReport(ledgerId, reportName, "2026-01", false);
            assertThat(exported.fileName()).isEqualTo(reportName.replace('_', '-') + "-2026-01.json");
            assertThat(new String(Base64.getDecoder().decode(exported.base64Content()), StandardCharsets.UTF_8))
                    .contains('"' + reportName + '"');
        }
    }

    @Test
    void agentCanImportFixedAssetsThroughMcpTools() throws IOException {
        UUID ownerId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-fixed-assets", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID assetAccountId = ledgerService.accountId(ledgerId, "1001");
        UUID otherAccountId = ledgerService.accountId(ledgerId, "3001");
        fixedAssetService.createCategory(ownerId, ledgerId, new FixedAssetRequests.CategoryCreate(
                "EQUIPMENT", "Equipment", 60, new BigDecimal("5"), assetAccountId,
                otherAccountId, otherAccountId, otherAccountId, otherAccountId, otherAccountId, otherAccountId));
        authenticate(ownerId);

        var result = tools.importFixedAssets(ledgerId, "fixed-assets.xlsx",
                Base64.getEncoder().encodeToString(fixedAssetWorkbook()));

        assertThat(result.errors()).isEmpty();
        assertThat(result.committed()).isTrue();
        assertThat(result.rowCount()).isEqualTo(1);
        assertThat(result.errorCount()).isZero();
        assertThat(jdbc.queryForObject(
                "select count(*) from fixed_asset where ledger_id = ? and code = 'FA-001'",
                Long.class, ledgerId)).isEqualTo(1L);
    }

    private VoucherRequests.Create request(UUID ledgerId) {
        return new VoucherRequests.Create(ledgerService.periodId(ledgerId, "2026-01"),
                LocalDate.of(2026, 1, 15), "GENERAL", "AGENT-1", "Agent draft", List.of(
                new VoucherRequests.Line(ledgerService.accountId(ledgerId, "1001"), "DEBIT", "CNY",
                        BigDecimal.ONE, BigDecimal.ONE, "Debit"),
                new VoucherRequests.Line(ledgerService.accountId(ledgerId, "3001"), "CREDIT", "CNY",
                        BigDecimal.ONE, BigDecimal.ONE, "Credit")));
    }

    private byte[] fixedAssetWorkbook() throws IOException {
        try (var workbook = new XSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("Assets");
            var header = sheet.createRow(0);
            for (int column = 0; column < 13; column++) {
                header.createCell(column).setCellValue("column-" + column);
            }
            var row = sheet.createRow(1);
            row.createCell(0).setCellValue("EQUIPMENT");
            row.createCell(1).setCellValue("FA-001");
            row.createCell(2).setCellValue("Test equipment");
            row.createCell(3).setCellValue("1");
            var dateStyle = workbook.createCellStyle();
            dateStyle.setDataFormat(workbook.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));
            var serviceDate = row.createCell(4);
            serviceDate.setCellValue(LocalDate.of(2025, 1, 1));
            serviceDate.setCellStyle(dateStyle);
            row.createCell(5).setCellValue("1200");
            row.createCell(6).setCellValue("0");
            row.createCell(7).setCellValue("60");
            row.createCell(8).setCellValue("5");
            row.createCell(9).setCellValue("100");
            row.createCell(10).setCellValue("5");
            row.createCell(11).setCellValue("0");
            row.createCell(12).setCellValue("Imported through MCP");
            workbook.write(output);
            return output.toByteArray();
        }
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private long auditCount(String traceId, String outcome) {
        return jdbc.queryForObject("""
                select count(*) from agent_tool_audit where trace_id = ? and outcome = ?
                """, Long.class, traceId, outcome);
    }

    private void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while waiting for asynchronous audit", exception);
            }
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private void authenticate(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", List.of()));
    }
}
