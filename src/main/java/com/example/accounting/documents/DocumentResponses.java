package com.example.accounting.documents;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class DocumentResponses {

    private DocumentResponses() {
    }

    @Schema(name = "DocumentResponse")
    public record Document(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID ledgerId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String objectKey,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String fileName,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String contentType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) long sizeBytes,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String sha256,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) boolean duplicateWarning,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) OffsetDateTime createdAt) {
    }

    public record Content(byte[] bytes, String fileName, String contentType) {
    }
}
