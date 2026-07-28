package com.example.accounting.documents;

import java.util.UUID;

public final class DocumentResponses {

    private DocumentResponses() {
    }

    public record Document(UUID id, UUID ledgerId, String objectKey, String fileName, String contentType,
                           long sizeBytes, String sha256, String status, boolean duplicateWarning) {
    }
}
