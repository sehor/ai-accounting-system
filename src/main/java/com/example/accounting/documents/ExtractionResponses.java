package com.example.accounting.documents;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

public final class ExtractionResponses {

    private ExtractionResponses() {
    }

    @Schema(name = "DocumentExtraction")
    public record Extraction(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) UUID documentId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String provider,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String status,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String structuredResult) {
    }
}
