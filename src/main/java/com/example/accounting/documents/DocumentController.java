package com.example.accounting.documents;

import com.example.accounting.identity.CurrentUserResolver;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/v1/ledgers/{ledgerId}/documents")
public class DocumentController {

    private final CurrentUserResolver currentUserResolver;
    private final DocumentService documentService;
    private final ExtractionService extractionService;

    public DocumentController(CurrentUserResolver currentUserResolver, DocumentService documentService,
                              ExtractionService extractionService) {
        this.currentUserResolver = currentUserResolver;
        this.documentService = documentService;
        this.extractionService = extractionService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentResponses.Document upload(HttpServletRequest request, @PathVariable UUID ledgerId,
                                             @RequestPart("file") MultipartFile file) throws IOException {
        return documentService.upload(user(request), ledgerId, file.getOriginalFilename(), file.getContentType(),
                file.getSize(), file.getInputStream());
    }

    @GetMapping("/{documentId}")
    public DocumentResponses.Document get(HttpServletRequest request, @PathVariable UUID ledgerId,
                                          @PathVariable UUID documentId) {
        return documentService.find(user(request), ledgerId, documentId);
    }

    @PostMapping("/{documentId}:extract")
    public ExtractionResponses.Extraction extractMock(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                       @PathVariable UUID documentId) {
        return extractionService.extractMock(user(request), ledgerId, documentId);
    }

    @GetMapping("/{documentId}/extractions")
    public List<ExtractionResponses.Extraction> extractions(HttpServletRequest request, @PathVariable UUID ledgerId,
                                                             @PathVariable UUID documentId) {
        return extractionService.list(user(request), ledgerId, documentId);
    }

    @PostMapping("/{documentId}:create-voucher-draft")
    public com.example.accounting.voucher.VoucherResponses.Voucher createVoucherDraft(
            HttpServletRequest request, @PathVariable UUID ledgerId, @PathVariable UUID documentId) {
        return extractionService.createVoucherDraft(user(request), ledgerId, documentId);
    }

    private UUID user(HttpServletRequest request) {
        return currentUserResolver.resolve(request);
    }
}
