package com.example.accounting.agent;

import com.example.accounting.agent.internal.port.AgentToolAuditRepository;
import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionResponses;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.JobResponses;
import com.example.accounting.documents.JobService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.reporting.ReportingService;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class FinanceMcpTools {

    private final CurrentUserResolver currentUserResolver;
    private final LedgerService ledgerService;
    private final ReportingService reportingService;
    private final VoucherService voucherService;
    private final DocumentService documentService;
    private final ExtractionService extractionService;
    private final JobService jobService;
    private final AgentToolAuditRepository audits;

    public FinanceMcpTools(CurrentUserResolver currentUserResolver, LedgerService ledgerService,
                           ReportingService reportingService, VoucherService voucherService,
                           DocumentService documentService, ExtractionService extractionService,
                           JobService jobService, AgentToolAuditRepository audits) {
        this.currentUserResolver = currentUserResolver;
        this.ledgerService = ledgerService;
        this.reportingService = reportingService;
        this.voucherService = voucherService;
        this.documentService = documentService;
        this.extractionService = extractionService;
        this.jobService = jobService;
        this.audits = audits;
    }

    @McpTool(name = "list_ledgers", description = "List ledgers available to the authenticated user")
    @PreAuthorize("isAuthenticated()")
    public List<LedgerResponses.Ledger> listLedgers() {
        UUID actorId = actor();
        return audited(actorId, "list_ledgers", null, "", () -> ledgerService.list(actorId));
    }

    @McpTool(name = "finance_query", description = "Run one whitelisted finance report query")
    @PreAuthorize("isAuthenticated()")
    public Object financeQuery(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "One of trial_balance, balance_sheet, income_statement, general_ledger, sub_ledger") String report,
            @McpToolParam(description = "Accounting period code, or null for all periods", required = false) String periodCode) {
        UUID actorId = actor();
        return audited(actorId, "finance_query", ledgerId, report + ":" + periodCode, () -> switch (report) {
            case "trial_balance" -> reportingService.trialBalance(actorId, ledgerId, periodCode);
            case "balance_sheet" -> reportingService.balanceSheet(actorId, ledgerId, periodCode);
            case "income_statement" -> reportingService.incomeStatement(actorId, ledgerId, periodCode);
            case "general_ledger" -> reportingService.generalLedger(actorId, ledgerId, periodCode);
            case "sub_ledger" -> reportingService.subLedger(actorId, ledgerId, periodCode);
            default -> throw new ApiProblemException(422, "FINANCE_QUERY_INVALID", "Invalid finance query",
                    "The report is not in the whitelist", false);
        });
    }

    @McpTool(name = "get_voucher", description = "Get one voucher visible in a ledger")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher getVoucher(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Voucher identifier") UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "get_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.find(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "create_voucher_draft", description = "Create a draft voucher; validation and posting are separate actions")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucherDraft(
            @McpToolParam(description = "Draft voucher payload") VoucherRequests.Create request,
            @McpToolParam(description = "Ledger identifier") UUID ledgerId) {
        UUID actorId = actor();
        return audited(actorId, "create_voucher_draft", ledgerId, request.toString(),
                () -> voucherService.create(actorId, ledgerId, request));
    }

    @McpTool(name = "validate_voucher", description = "Validate a voucher without posting it")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher validateVoucher(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Voucher identifier") UUID voucherId) {
        UUID actorId = actor();
        return audited(actorId, "validate_voucher", ledgerId, voucherId.toString(),
                () -> voucherService.validate(actorId, ledgerId, voucherId));
    }

    @McpTool(name = "upload_document", description = "Upload a base64 encoded PDF, JPEG, or PNG document")
    @PreAuthorize("isAuthenticated()")
    public DocumentResponses.Document uploadDocument(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Original file name") String fileName,
            @McpToolParam(description = "MIME type") String contentType,
            @McpToolParam(description = "Base64 file content") String base64Content) {
        UUID actorId = actor();
        // ponytail: MCP JSON base64 buffers payload; REST upload remains streaming until binary MCP transport is needed.
        byte[] content;
        try {
            content = Base64.getDecoder().decode(base64Content);
        } catch (IllegalArgumentException exception) {
            throw new ApiProblemException(400, "DOCUMENT_CONTENT_INVALID", "Invalid document content",
                    "The document content must be base64", false);
        }
        return audited(actorId, "upload_document", ledgerId, fileName + ":" + content.length,
                () -> documentService.upload(actorId, ledgerId, fileName, contentType, content.length,
                        new ByteArrayInputStream(content)));
    }

    @McpTool(name = "extract_document", description = "Run the mock document extractor")
    @PreAuthorize("isAuthenticated()")
    public ExtractionResponses.Extraction extractDocument(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Document identifier") UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "extract_document", ledgerId, documentId.toString(),
                () -> extractionService.extractMock(actorId, ledgerId, documentId));
    }

    @McpTool(name = "get_job_status", description = "Get the status of a background job")
    @PreAuthorize("isAuthenticated()")
    public JobResponses.Job getJobStatus(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Job identifier") UUID jobId) {
        UUID actorId = actor();
        return audited(actorId, "get_job_status", ledgerId, jobId.toString(),
                () -> jobService.find(actorId, ledgerId, jobId));
    }

    @McpTool(name = "create_voucher_draft_from_document", description = "Create an idempotent voucher draft from a document extraction")
    @PreAuthorize("isAuthenticated()")
    public VoucherResponses.Voucher createVoucherDraftFromDocument(
            @McpToolParam(description = "Ledger identifier") UUID ledgerId,
            @McpToolParam(description = "Document identifier") UUID documentId) {
        UUID actorId = actor();
        return audited(actorId, "create_voucher_draft_from_document", ledgerId, documentId.toString(),
                () -> extractionService.createVoucherDraft(actorId, ledgerId, documentId));
    }

    private UUID actor() {
        return currentUserResolver.resolveAuthenticatedUser();
    }

    private <T> T audited(UUID actorId, String toolName, UUID ledgerId, String input, Supplier<T> action) {
        T result = action.get();
        audits.record(toolName, ledgerId, actorId, UUID.randomUUID(), hash(input), hash(result));
        return result;
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to hash MCP audit value", exception);
        }
    }
}
