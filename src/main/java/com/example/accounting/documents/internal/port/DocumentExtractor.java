package com.example.accounting.documents.internal.port;

import com.example.accounting.documents.DocumentResponses;

public interface DocumentExtractor {

    Result extract(DocumentResponses.Document document, byte[] content);

    record Result(String provider, String providerVersion, String structuredResult, String sourceReferences) {
    }
}
