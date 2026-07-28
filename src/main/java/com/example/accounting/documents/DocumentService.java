package com.example.accounting.documents;

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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentService {

    private static final long MAX_SIZE = 20 * 1024 * 1024L;
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    private final JdbcTemplate jdbcTemplate;
    private final Path storageRoot;

    public DocumentService(JdbcTemplate jdbcTemplate,
                           @Value("${storage.local.root:./data/files}") String storageRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.storageRoot = Path.of(storageRoot);
    }

    @Transactional
    public DocumentResponses.Document upload(UUID actorId, UUID ledgerId, String fileName, String contentType,
                                             long declaredSize, InputStream input) {
        requireRole(actorId, ledgerId);
        if (!CONTENT_TYPES.contains(contentType) || fileName == null || fileName.isBlank()) {
            throw problem(415, "UNSUPPORTED_DOCUMENT_TYPE", "Unsupported document type",
                    "Only PDF, JPEG and PNG files are accepted");
        }
        if (declaredSize < 0 || declaredSize > MAX_SIZE) {
            throw problem(413, "DOCUMENT_TOO_LARGE", "Document is too large", "The maximum document size is 20 MB");
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
                        throw problem(413, "DOCUMENT_TOO_LARGE", "Document is too large", "The maximum document size is 20 MB");
                    }
                    digest.update(buffer, 0, read);
                    output.write(buffer, 0, read);
                }
            }
            String sha256 = HexFormat.of().formatHex(digest.digest());
            boolean duplicate = Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    "select exists (select 1 from document where ledger_id = ? and sha256 = ?)", Boolean.class,
                    ledgerId, sha256));
            UUID id = UUID.randomUUID();
            jdbcTemplate.update("""
                    insert into document (id, ledger_id, object_key, file_name, content_type, size_bytes, sha256,
                        duplicate_warning, created_by)
                    values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                    """, id, ledgerId, objectKey, fileName, contentType, size, sha256,
                    duplicate ? "{\"duplicate\":true}" : null, actorId);
            jdbcTemplate.update("""
                    insert into background_job (id, ledger_id, job_type, aggregate_type, aggregate_id, payload)
                    values (?, ?, 'EXTRACT_DOCUMENT', 'DOCUMENT', ?, ?::jsonb)
                    """, UUID.randomUUID(), ledgerId, id, "{}");
            return new DocumentResponses.Document(id, ledgerId, objectKey, fileName, contentType, size, sha256,
                    "UPLOADED", duplicate);
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

    @Transactional(readOnly = true)
    public DocumentResponses.Document find(UUID actorId, UUID ledgerId, UUID documentId) {
        requireRole(actorId, ledgerId);
        DocumentResponses.Document document = jdbcTemplate.query("""
                select id, ledger_id, object_key, file_name, content_type, size_bytes, sha256, status,
                    duplicate_warning is not null duplicate_warning
                from document where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new DocumentResponses.Document(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("object_key"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getBoolean("duplicate_warning")) : null, ledgerId, documentId);
        if (document == null) {
            throw problem(404, "DOCUMENT_NOT_FOUND", "Document not found", "The document is not available to this ledger");
        }
        return document;
    }

    private void requireRole(UUID actorId, UUID ledgerId) {
        String role = jdbcTemplate.query("""
                select m.role from ledger_membership m join ledger l on l.id = m.ledger_id
                where m.ledger_id = ? and m.user_id = ? and m.status = 'ACTIVE'
                    and m.deleted_at is null and l.deleted_at is null
                """, rs -> rs.next() ? rs.getString(1) : null, ledgerId, actorId);
        if (role == null) {
            throw problem(404, "LEDGER_NOT_FOUND", "Ledger not found", "The ledger is not available to this user");
        }
        if (!Set.of(LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.AGENT).contains(LedgerRole.valueOf(role))) {
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

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }
}
