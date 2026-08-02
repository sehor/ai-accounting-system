package com.example.accounting.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.identity.IdentityService;
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
    void agentCanCreateAndValidateDraftOnlyThroughMcpTools() {
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

        assertThat(draft.status()).isEqualTo("DRAFT");
        assertThat(retriedDraft.id()).isEqualTo(draft.id());
        assertThat(retriedDocument.id()).isEqualTo(document.id());
        assertThat(tools.validateVoucher(ledgerId, draft.id()).status()).isEqualTo("VALIDATED");
        assertThatThrownBy(() -> voucherService.post(agentId, ledgerId, draft.id()))
                .isInstanceOf(ApiProblemException.class);
        var posted = tools.postVoucher(ledgerId, draft.id());
        assertThat(posted.status()).isEqualTo("POSTED");
        assertThat(tools.postVoucher(ledgerId, draft.id()).id()).isEqualTo(posted.id());
        assertThat(jdbc.queryForObject("""
                select count(*) from agent_tool_audit where trace_id = ? and outcome = 'SUCCESS'
                """, Long.class, traceId)).isEqualTo(7L);
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
    void agentCannotBypassApprovalWhenPosting() {
        UUID ownerId = UUID.randomUUID();
        UUID agentId = UUID.randomUUID();
        identityService.ensureUser(user(agentId));
        UUID ledgerId = ledgerService.create(user(ownerId), new LedgerRequests.Create(
                "agent-approval", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), true)).id();
        ledgerService.addMember(ownerId, ledgerId, new LedgerRequests.AddMember(agentId, LedgerRole.AGENT));
        authenticate(agentId);

        var draft = tools.createVoucherDraft(request(ledgerId), ledgerId, "approval-draft");
        tools.validateVoucher(ledgerId, draft.id());

        assertThatThrownBy(() -> tools.postVoucher(ledgerId, draft.id()))
                .isInstanceOf(ApiProblemException.class);
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
