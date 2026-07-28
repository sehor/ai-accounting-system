package com.example.accounting.documents.internal.application;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionResponses;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.internal.port.DocumentRepository;
import com.example.accounting.documents.internal.port.ExtractionRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultExtractionService implements ExtractionService {

    private final DocumentService documentService;
    private final VoucherService voucherService;
    private final DocumentRepository documents;
    private final ExtractionRepository extractions;
    private final LedgerAccessService ledgerAccess;

    public DefaultExtractionService(DocumentService documentService, VoucherService voucherService,
                                    DocumentRepository documents, ExtractionRepository extractions,
                                    LedgerAccessService ledgerAccess) {
        this.documentService = documentService;
        this.voucherService = voucherService;
        this.documents = documents;
        this.extractions = extractions;
        this.ledgerAccess = ledgerAccess;
    }

    @Override
    @Transactional
    public ExtractionResponses.Extraction extractMock(UUID actorId, UUID ledgerId, UUID documentId) {
        requireWriteRole(actorId, ledgerId);
        DocumentResponses.Document document = documentService.find(actorId, ledgerId, documentId);
        String result = "{\"documentId\":\"" + documentId + "\",\"fileName\":\""
                + escape(document.fileName()) + "\",\"totalAmount\":1,\"currency\":\"CNY\"}";
        UUID extractionId = UUID.randomUUID();
        extractions.create(extractionId, ledgerId, documentId, result, document.sha256(), hash(result));
        documents.markExtracted(ledgerId, documentId);
        return new ExtractionResponses.Extraction(extractionId, documentId, "mock", "SUCCEEDED", result);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ExtractionResponses.Extraction> list(UUID actorId, UUID ledgerId, UUID documentId) {
        documentService.find(actorId, ledgerId, documentId);
        return extractions.list(ledgerId, documentId);
    }

    @Override
    @Transactional
    public VoucherResponses.Voucher createVoucherDraft(UUID actorId, UUID ledgerId, UUID documentId) {
        requireWriteRole(actorId, ledgerId);
        documentService.find(actorId, ledgerId, documentId);
        ExtractionRepository.OpenPeriod period = extractions.firstOpenPeriod(ledgerId)
                .orElseThrow(() -> problem("The ledger has no open period for the mock extraction"));
        UUID debitAccount = account(ledgerId, "1001");
        UUID creditAccount = account(ledgerId, "3001");
        VoucherRequests.Create request = new VoucherRequests.Create(period.id(), period.startDate(), "GENERAL",
                "DOC-" + documentId.toString().substring(0, 8), "Mock extraction draft",
                List.of(new VoucherRequests.Line(debitAccount, "DEBIT", "CNY", BigDecimal.ONE,
                                BigDecimal.ONE, "Mock extraction"),
                        new VoucherRequests.Line(creditAccount, "CREDIT", "CNY", BigDecimal.ONE,
                                BigDecimal.ONE, "Mock extraction")));
        return voucherService.create(actorId, ledgerId, request, "document-extraction:" + documentId);
    }

    private UUID account(UUID ledgerId, String code) {
        return extractions.findAccount(ledgerId, code)
                .orElseThrow(() -> problem("The ledger has no default accounts for the mock extraction"));
    }

    private void requireWriteRole(UUID actorId, UUID ledgerId) {
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT)
                .contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem("The current user cannot process documents");
        }
    }

    private ApiProblemException problem(String detail) {
        return new ApiProblemException(422, "EXTRACTION_DRAFT_UNSUPPORTED", "Extraction draft unsupported",
                detail, false);
    }

    private String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new ApiProblemException(500, "EXTRACTION_HASH_FAILED", "Extraction hash failed",
                    "The extraction result could not be hashed", false);
        }
    }
}
