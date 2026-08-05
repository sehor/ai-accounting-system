package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
import com.example.accounting.ledger.AccountExchangeService;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.audit.AuditContext;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@SpringBootTest
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
        assertThat(jdbc.queryForObject("""
                select count(*) from agent_tool_audit where trace_id = ? and outcome = 'SUCCESS'
                """, Long.class, traceId)).isEqualTo(4L);
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

        var audit = jdbc.queryForMap("""
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

        assertThat(retried.id()).isEqualTo(account.id());
        assertThat(tools.listPeriods(ledgerId))
                .anyMatch(period -> period.periodCode().equals("2026-06"));
        assertThatThrownBy(() -> tools.ensureAccount(ledgerId, new LedgerRequests.AccountCreate(
                "100201", "冲突科目", "ASSET", "DEBIT")))
                .isInstanceOf(ApiProblemException.class);
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

    private VoucherRequests.Create request(UUID ledgerId) {
        return new VoucherRequests.Create(ledgerService.periodId(ledgerId, "2026-01"),
                LocalDate.of(2026, 1, 15), "GENERAL", "AGENT-1", "Agent draft", List.of(
                new VoucherRequests.Line(ledgerService.accountId(ledgerId, "1001"), "DEBIT", "CNY",
                        BigDecimal.ONE, BigDecimal.ONE, "Debit"),
                new VoucherRequests.Line(ledgerService.accountId(ledgerId, "3001"), "CREDIT", "CNY",
                        BigDecimal.ONE, BigDecimal.ONE, "Credit")));
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private void authenticate(UUID id) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), "n/a", List.of()));
    }
}
