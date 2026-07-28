package com.example.accounting.documents;

import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.HexFormat;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ExtractionService {

    private final JdbcTemplate jdbcTemplate;
    private final DocumentService documentService;
    private final com.example.accounting.voucher.VoucherService voucherService;

    public ExtractionService(JdbcTemplate jdbcTemplate, DocumentService documentService,
                             com.example.accounting.voucher.VoucherService voucherService) {
        this.jdbcTemplate = jdbcTemplate;
        this.documentService = documentService;
        this.voucherService = voucherService;
    }

    @Transactional
    public ExtractionResponses.Extraction extractMock(UUID actorId, UUID ledgerId, UUID documentId) {
        DocumentResponses.Document document = documentService.find(actorId, ledgerId, documentId);
        String result = "{\"documentId\":\"" + documentId + "\",\"fileName\":\""
                + escape(document.fileName()) + "\",\"totalAmount\":1,\"currency\":\"CNY\"}";
        String outputHash = hash(result);
        UUID extractionId = UUID.randomUUID();
        jdbcTemplate.update("""
                insert into document_extraction (id, ledger_id, document_id, provider, provider_version,
                    structured_result, source_references, input_hash, output_hash)
                values (?, ?, ?, 'mock', 'v1', ?::jsonb, ?::jsonb, ?, ?)
                """, extractionId, ledgerId, documentId, result, "{}", document.sha256(), outputHash);
        jdbcTemplate.update("update document set status = 'EXTRACTED' where ledger_id = ? and id = ?",
                ledgerId, documentId);
        return new ExtractionResponses.Extraction(extractionId, documentId, "mock", "SUCCEEDED", result);
    }

    @Transactional(readOnly = true)
    public List<ExtractionResponses.Extraction> list(UUID actorId, UUID ledgerId, UUID documentId) {
        documentService.find(actorId, ledgerId, documentId);
        return jdbcTemplate.query("""
                select id, document_id, provider, status, structured_result::text
                from document_extraction where ledger_id = ? and document_id = ? order by created_at
                """, (rs, rowNum) -> new ExtractionResponses.Extraction(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("provider"), rs.getString("status"),
                rs.getString("structured_result")), ledgerId, documentId);
    }

    @Transactional
    public VoucherResponses.Voucher createVoucherDraft(UUID actorId, UUID ledgerId, UUID documentId) {
        documentService.find(actorId, ledgerId, documentId);
        UUID periodId = jdbcTemplate.queryForObject("""
                select id from accounting_period where ledger_id = ? and status = 'OPEN'
                order by period_code limit 1
                """, UUID.class, ledgerId);
        LocalDate voucherDate = jdbcTemplate.queryForObject(
                "select start_date from accounting_period where ledger_id = ? and id = ?",
                LocalDate.class, ledgerId, periodId);
        UUID debitAccount = account(ledgerId, "1001");
        UUID creditAccount = account(ledgerId, "3001");
        VoucherRequests.Create request = new VoucherRequests.Create(periodId, voucherDate, "GENERAL",
                "DOC-" + documentId.toString().substring(0, 8), "Mock extraction draft",
                List.of(new VoucherRequests.Line(debitAccount, "DEBIT", "CNY", BigDecimal.ONE,
                                BigDecimal.ONE, "Mock extraction"),
                        new VoucherRequests.Line(creditAccount, "CREDIT", "CNY", BigDecimal.ONE,
                                BigDecimal.ONE, "Mock extraction")));
        return voucherService.create(actorId, ledgerId, request, "document-extraction:" + documentId);
    }

    private UUID account(UUID ledgerId, String code) {
        UUID account = jdbcTemplate.query("select id from ledger_account where ledger_id = ? and code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId, code);
        if (account == null) {
            throw new ApiProblemException(422, "EXTRACTION_DRAFT_UNSUPPORTED", "Extraction draft unsupported",
                    "The ledger has no default accounts for the mock extraction", false);
        }
        return account;
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
