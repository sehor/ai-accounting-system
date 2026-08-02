package com.example.accounting.documents.internal.application;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.ExtractionResponses;
import com.example.accounting.documents.ExtractionService;
import com.example.accounting.documents.internal.port.DocumentExtractor;
import com.example.accounting.documents.internal.port.DocumentRepository;
import com.example.accounting.documents.internal.port.ExtractionRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final DocumentExtractor extractor;
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    public DefaultExtractionService(DocumentService documentService, VoucherService voucherService,
                                    DocumentRepository documents, ExtractionRepository extractions,
                                    LedgerAccessService ledgerAccess, DocumentExtractor extractor) {
        this.documentService = documentService;
        this.voucherService = voucherService;
        this.documents = documents;
        this.extractions = extractions;
        this.ledgerAccess = ledgerAccess;
        this.extractor = extractor;
    }

    @Override
    @Transactional
    public ExtractionResponses.Extraction extract(UUID actorId, UUID ledgerId, UUID documentId) {
        requireWriteRole(actorId, ledgerId);
        DocumentResponses.Document document = documentService.find(actorId, ledgerId, documentId);
        var existing = extractions.list(ledgerId, documentId).stream()
                .filter(item -> !"mock".equals(item.provider())).reduce((first, second) -> second);
        if (existing.isPresent()) {
            return existing.get();
        }
        DocumentExtractor.Result result = extractor.extract(
                document, documentService.content(actorId, ledgerId, documentId).bytes());
        UUID extractionId = UUID.randomUUID();
        extractions.create(extractionId, ledgerId, documentId, result, document.sha256(),
                hash(result.structuredResult()));
        documents.markExtracted(ledgerId, documentId);
        return new ExtractionResponses.Extraction(
                extractionId, documentId, result.provider(), "SUCCEEDED", result.structuredResult());
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
        return createVoucherDraft(actorId, ledgerId, documentId, false);
    }

    @Override
    @Transactional
    public VoucherResponses.Voucher createAgentVoucherDraft(UUID actorId, UUID ledgerId, UUID documentId) {
        return createVoucherDraft(actorId, ledgerId, documentId, true);
    }

    private VoucherResponses.Voucher createVoucherDraft(
            UUID actorId, UUID ledgerId, UUID documentId, boolean agentTool) {
        requireWriteRole(actorId, ledgerId);
        documentService.find(actorId, ledgerId, documentId);
        ExtractionRepository.OpenPeriod period = extractions.firstOpenPeriod(ledgerId)
                .orElseThrow(() -> problem("The ledger has no open period for the extraction"));
        UUID debitAccount = account(ledgerId, "1001");
        UUID creditAccount = account(ledgerId, "3001");
        ExtractionResponses.Extraction extraction = extractions.list(ledgerId, documentId).stream()
                .filter(item -> !"mock".equals(item.provider())).reduce((first, second) -> second)
                .orElseThrow(() -> problem("The document has no successful real extraction"));
        BigDecimal amount;
        BigDecimal exchangeRate;
        String currency;
        try {
            var result = objectMapper.readTree(extraction.structuredResult());
            amount = new BigDecimal(result.path("totalAmount").asText());
            exchangeRate = new BigDecimal(result.path("exchangeRate").asText());
            currency = result.path("currency").asText();
        } catch (Exception exception) {
            throw problem("The extraction result cannot be converted to a voucher");
        }
        // ponytail: v1 uses fixed clearing accounts; replace with reviewed account-classification rules when needed.
        VoucherRequests.Create request = new VoucherRequests.Create(period.id(), period.startDate(), "GENERAL",
                "DOC-" + documentId.toString().substring(0, 8), "Document extraction draft",
                List.of(new VoucherRequests.Line(debitAccount, "DEBIT", currency, amount,
                                exchangeRate, "Document extraction"),
                        new VoucherRequests.Line(creditAccount, "CREDIT", currency, amount,
                                exchangeRate, "Document extraction")));
        String key = "document-extraction:" + documentId;
        return agentTool
                ? voucherService.createAgentDraft(actorId, ledgerId, request, key)
                : voucherService.create(actorId, ledgerId, request, key);
    }

    private UUID account(UUID ledgerId, String code) {
        return extractions.findAccount(ledgerId, code)
                .orElseThrow(() -> problem("The ledger has no default accounts for the extraction"));
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
