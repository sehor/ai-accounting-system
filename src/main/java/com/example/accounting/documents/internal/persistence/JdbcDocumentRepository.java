package com.example.accounting.documents.internal.persistence;

import com.example.accounting.documents.DocumentResponses;
import com.example.accounting.documents.internal.port.DocumentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcDocumentRepository implements DocumentRepository {

    private final JdbcTemplate jdbc;

    public JdbcDocumentRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean existsByHash(UUID ledgerId, String sha256) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "select exists (select 1 from document where ledger_id = ? and sha256 = ?)",
                Boolean.class, ledgerId, sha256));
    }

    @Override
    public void create(DocumentResponses.Document document, UUID actorId) {
        jdbc.update("""
                insert into document (id, ledger_id, object_key, file_name, content_type, size_bytes, sha256,
                    duplicate_warning, created_by)
                values (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                """, document.id(), document.ledgerId(), document.objectKey(), document.fileName(),
                document.contentType(), document.sizeBytes(), document.sha256(),
                document.duplicateWarning() ? "{\"duplicate\":true}" : null, actorId);
    }

    @Override
    public void enqueueExtraction(UUID ledgerId, UUID documentId) {
        jdbc.update("""
                insert into background_job (id, ledger_id, job_type, aggregate_type, aggregate_id, payload)
                values (?, ?, 'EXTRACT_DOCUMENT', 'DOCUMENT', ?, ?::jsonb)
                """, UUID.randomUUID(), ledgerId, documentId, "{}");
    }

    @Override
    public Optional<DocumentResponses.Document> find(UUID ledgerId, UUID documentId) {
        return Optional.ofNullable(jdbc.query("""
                select id, ledger_id, object_key, file_name, content_type, size_bytes, sha256, status,
                    duplicate_warning is not null duplicate_warning, created_at
                from document where ledger_id = ? and id = ?
                """, rs -> rs.next() ? new DocumentResponses.Document(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("object_key"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getBoolean("duplicate_warning"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)) : null, ledgerId, documentId));
    }

    @Override
    public List<DocumentResponses.Document> list(UUID ledgerId, int limit, int offset) {
        return jdbc.query("""
                select id, ledger_id, object_key, file_name, content_type, size_bytes, sha256, status,
                    duplicate_warning is not null duplicate_warning, created_at
                from document where ledger_id = ? order by created_at desc, id desc limit ? offset ?
                """, (rs, rowNum) -> new DocumentResponses.Document(rs.getObject("id", UUID.class),
                rs.getObject("ledger_id", UUID.class), rs.getString("object_key"), rs.getString("file_name"),
                rs.getString("content_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                rs.getString("status"), rs.getBoolean("duplicate_warning"),
                rs.getObject("created_at", java.time.OffsetDateTime.class)), ledgerId, limit, offset);
    }

    @Override
    public void markExtracted(UUID ledgerId, UUID documentId) {
        jdbc.update("update document set status = 'EXTRACTED' where ledger_id = ? and id = ?", ledgerId, documentId);
    }
}
