package com.example.accounting.documents.internal.application;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.DocumentService;
import com.example.accounting.documents.internal.port.DocumentRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class DefaultDocumentService implements DocumentService {

    private static final long MAX_SIZE = 20 * 1024 * 1024L;
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");
    private static final Set<LedgerRole> WRITE_ROLES = Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT);

    private final LedgerAccessService ledgerAccess;
    private final DocumentRepository documents;
    private final Path storageRoot;

    public DefaultDocumentService(LedgerAccessService ledgerAccess, DocumentRepository documents,
                                  @Value("${storage.local.root:./data/files}") String storageRoot) {
        this.ledgerAccess = ledgerAccess;
        this.documents = documents;
        this.storageRoot = Path.of(storageRoot);
    }

    @Override
    @Transactional
    public DocumentResponses.Document upload(UUID actorId, UUID ledgerId, String fileName, String contentType,
                                             long declaredSize, InputStream input) {
        return upload(actorId, ledgerId, fileName, contentType, declaredSize, input, null);
    }

    @Override
    @Transactional
    public DocumentResponses.Document upload(UUID actorId, UUID ledgerId, String fileName, String contentType,
                                             long declaredSize, InputStream input, String idempotencyKey) {
        requireWriteRole(actorId, ledgerId);
        if (!CONTENT_TYPES.contains(contentType) || fileName == null || fileName.isBlank()) {
            throw problem(415, "UNSUPPORTED_DOCUMENT_TYPE", "Unsupported document type",
                    "Only PDF, JPEG and PNG files are accepted");
        }
        if (declaredSize < 0 || declaredSize > MAX_SIZE) {
            throw problem(413, "DOCUMENT_TOO_LARGE", "Document is too large", "The maximum document size is 20 MB");
        }
        String key = idempotencyKey == null || idempotencyKey.isBlank() ? null : idempotencyKey.trim();
        if (key != null && key.length() > 128) {
            throw problem(400, "IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key",
                    "The idempotency key is too long");
        }
        String objectKey = UUID.randomUUID().toString();
        Path root = storageRoot.toAbsolutePath().normalize();
        Path target = root.resolve(objectKey).normalize();
        if (!target.startsWith(root)) {
            throw problem(400, "INVALID_DOCUMENT_PATH", "Invalid document path", "The document path is invalid");
        }
        long size = 0;
        try {
            Files.createDirectories(root);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream source = input; var output = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = source.read(buffer)) >= 0) {
                    size += read;
                    if (size > MAX_SIZE) {
                        throw problem(413, "DOCUMENT_TOO_LARGE", "Document is too large",
                                "The maximum document size is 20 MB");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            deleteFileIfTransactionRollsBack(target);
            String sha256 = HexFormat.of().formatHex(digest.digest());
            boolean duplicate = documents.existsByHash(ledgerId, sha256);
            UUID id = UUID.randomUUID();
            if (key != null) {
                String requestHash = hash(fileName + "\0" + contentType + "\0" + size + "\0" + sha256);
                if (!documents.reserveIdempotency(ledgerId, actorId, key, requestHash, id)) {
                    DocumentRepository.DocumentIdempotency existing =
                            documents.findIdempotency(ledgerId, actorId, key).orElseThrow();
                    if (!requestHash.equals(existing.requestHash())) {
                        throw problem(409, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                                "The idempotency key was used with a different request");
                    }
                    deleteQuietly(target);
                    return documents.find(ledgerId, existing.documentId()).orElseThrow();
                }
            }
            DocumentResponses.Document document = new DocumentResponses.Document(
                    id, ledgerId, objectKey, fileName, contentType, size, sha256, "UPLOADED", duplicate,
                    java.time.OffsetDateTime.now());
            documents.create(document, actorId);
            documents.enqueueExtraction(ledgerId, id);
            return document;
        } catch (IOException exception) {
            deleteQuietly(target);
            throw problem(500, "DOCUMENT_STORAGE_FAILED", "Document storage failed", "The document could not be stored");
        } catch (NoSuchAlgorithmException exception) {
            deleteQuietly(target);
            throw problem(500, "DOCUMENT_HASH_FAILED", "Document hash failed", "SHA-256 is unavailable");
        } catch (RuntimeException exception) {
            deleteQuietly(target);
            throw exception;
        }
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponses.Document find(UUID actorId, UUID ledgerId, UUID documentId) {
        requireReadRole(actorId, ledgerId);
        return documents.find(ledgerId, documentId).orElseThrow(() ->
                problem(404, "DOCUMENT_NOT_FOUND", "Document not found",
                        "The document is not available to this ledger"));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DocumentResponses.Document> list(UUID actorId, UUID ledgerId, int limit, int offset) {
        requireReadRole(actorId, ledgerId);
        if (limit < 1 || limit > 100 || offset < 0) {
            throw problem(400, "PAGINATION_INVALID", "Invalid pagination",
                    "limit must be between 1 and 100 and offset must be non-negative");
        }
        return documents.list(ledgerId, limit, offset);
    }

    @Override
    @Transactional(readOnly = true)
    public DocumentResponses.Content content(UUID actorId, UUID ledgerId, UUID documentId) {
        requireReadRole(actorId, ledgerId);
        DocumentResponses.Document document = documents.find(ledgerId, documentId).orElseThrow(() ->
                problem(404, "DOCUMENT_NOT_FOUND", "Document not found",
                        "The document is not available to this ledger"));
        Path root = storageRoot.toAbsolutePath().normalize();
        Path target = root.resolve(document.objectKey()).normalize();
        if (!target.startsWith(root)) {
            throw problem(400, "INVALID_DOCUMENT_PATH", "Invalid document path", "The document path is invalid");
        }
        try {
            return new DocumentResponses.Content(Files.readAllBytes(target), document.fileName(),
                    document.contentType());
        } catch (IOException exception) {
            throw problem(404, "DOCUMENT_CONTENT_NOT_FOUND", "Document content not found",
                    "The document content is not available");
        }
    }

    private void requireReadRole(UUID actorId, UUID ledgerId) {
        ledgerAccess.requireMembership(actorId, ledgerId);
    }

    private void requireWriteRole(UUID actorId, UUID ledgerId) {
        if (!WRITE_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw problem(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot upload documents");
        }
    }

    private void deleteQuietly(Path target) {
        try {
            Files.deleteIfExists(target);
        } catch (IOException ignored) {
        }
    }

    private String hash(String value) throws NoSuchAlgorithmException {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private void deleteFileIfTransactionRollsBack(Path target) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        deleteQuietly(target);
                    }
                }
            });
        }
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
