package com.example.accounting.documents.internal.port;

import com.example.accounting.documents.DocumentResponses;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRepository {

    boolean existsByHash(UUID ledgerId, String sha256);

    void create(DocumentResponses.Document document, UUID actorId);

    void enqueueExtraction(UUID ledgerId, UUID documentId);

    Optional<DocumentResponses.Document> find(UUID ledgerId, UUID documentId);

    List<DocumentResponses.Document> list(UUID ledgerId, int limit, int offset);

    void markExtracted(UUID ledgerId, UUID documentId);
}
