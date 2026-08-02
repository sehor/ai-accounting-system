package com.example.accounting.documents.internal.port;

import com.example.accounting.documents.DocumentResponses;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    boolean existsByHash(UUID ledgerId, String sha256);

    void create(DocumentResponses.Document document, UUID actorId);

    boolean reserveIdempotency(UUID ledgerId, UUID actorId, String key, String requestHash, UUID documentId);

    Optional<DocumentIdempotency> findIdempotency(UUID ledgerId, UUID actorId, String key);

    void enqueueExtraction(UUID ledgerId, UUID documentId);

    Optional<DocumentResponses.Document> find(UUID ledgerId, UUID documentId);

    List<DocumentResponses.Document> list(UUID ledgerId, int limit, int offset);

    void markExtracted(UUID ledgerId, UUID documentId);

    record DocumentIdempotency(String requestHash, UUID documentId) {
    }
}
