package com.example.accounting.documents;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.ledger.LedgerRequests;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class Stage5DocumentTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private DocumentService documentService;

    @Autowired
    private JobService jobService;

    @Autowired
    private ExtractionService extractionService;

    @Test
    void streamsUploadWarnsOnDuplicateAndClaimsJob() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("documents", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        byte[] content = "pdf-like content".getBytes(StandardCharsets.UTF_8);

        DocumentResponses.Document first = documentService.upload(userId, ledgerId, "invoice.pdf", "application/pdf",
                content.length, new ByteArrayInputStream(content));
        DocumentResponses.Document second = documentService.upload(userId, ledgerId, "invoice-copy.pdf", "application/pdf",
                content.length, new ByteArrayInputStream(content));

        assertThat(first.duplicateWarning()).isFalse();
        assertThat(second.duplicateWarning()).isTrue();
        assertThat(Files.exists(Path.of("data/files", first.objectKey()))).isTrue();
        JobResponses.Job job = jobService.claimOne(userId.toString());
        assertThat(job).isNotNull();
        assertThat(jobService.complete(job.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(extractionService.extractMock(userId, ledgerId, first.id()).status()).isEqualTo("SUCCEEDED");
        assertThat(extractionService.list(userId, ledgerId, first.id())).hasSize(1);
        assertThat(extractionService.createVoucherDraft(userId, ledgerId, first.id()).status()).isEqualTo("DRAFT");
        assertThat(documentService.find(userId, ledgerId, first.id()).status()).isEqualTo("EXTRACTED");
    }

    @Test
    void rejectsUnsupportedTypeAndOversizedUpload() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                new LedgerRequests.Create("documents-invalid", "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();

        assertThatThrownBy(() -> documentService.upload(userId, ledgerId, "invoice.exe", "application/octet-stream",
                1, new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> documentService.upload(userId, ledgerId, "invoice.pdf", "application/pdf",
                20 * 1024 * 1024L + 1, new ByteArrayInputStream(new byte[]{1}))).isInstanceOf(ApiProblemException.class);
    }
}
