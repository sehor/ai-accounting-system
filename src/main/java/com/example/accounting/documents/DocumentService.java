package com.example.accounting.documents;

import java.io.InputStream;
import java.util.List;
import java.util.UUID;

public interface DocumentService {

    DocumentResponses.Document upload(UUID actorId, UUID ledgerId, String fileName, String contentType,
                                      long declaredSize, InputStream input);

    DocumentResponses.Document find(UUID actorId, UUID ledgerId, UUID documentId);

    List<DocumentResponses.Document> list(UUID actorId, UUID ledgerId, int limit, int offset);

    DocumentResponses.Content content(UUID actorId, UUID ledgerId, UUID documentId);
}
