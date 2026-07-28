package com.example.accounting.documents;

import java.util.UUID;

public final class ExtractionResponses {

    private ExtractionResponses() {
    }

    public record Extraction(UUID id, UUID documentId, String provider, String status, String structuredResult) {
    }
}
