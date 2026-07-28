package com.example.accounting.documents.internal.persistence;

import com.example.accounting.documents.ExtractionResponses;
import com.example.accounting.documents.internal.port.ExtractionRepository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcExtractionRepository implements ExtractionRepository {

    private final JdbcTemplate jdbc;

    public JdbcExtractionRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void create(UUID extractionId, UUID ledgerId, UUID documentId, String result,
                       String inputHash, String outputHash) {
        jdbc.update("""
                insert into document_extraction (id, ledger_id, document_id, provider, provider_version,
                    structured_result, source_references, input_hash, output_hash)
                values (?, ?, ?, 'mock', 'v1', ?::jsonb, ?::jsonb, ?, ?)
                """, extractionId, ledgerId, documentId, result, "{}", inputHash, outputHash);
    }

    @Override
    public List<ExtractionResponses.Extraction> list(UUID ledgerId, UUID documentId) {
        return jdbc.query("""
                select id, document_id, provider, status, structured_result::text
                from document_extraction where ledger_id = ? and document_id = ? order by created_at
                """, (rs, rowNum) -> new ExtractionResponses.Extraction(rs.getObject("id", UUID.class),
                rs.getObject("document_id", UUID.class), rs.getString("provider"), rs.getString("status"),
                rs.getString("structured_result")), ledgerId, documentId);
    }

    @Override
    public Optional<OpenPeriod> firstOpenPeriod(UUID ledgerId) {
        return Optional.ofNullable(jdbc.query("""
                select id, start_date from accounting_period
                where ledger_id = ? and status = 'OPEN' order by period_code limit 1
                """, rs -> rs.next() ? new OpenPeriod(rs.getObject("id", UUID.class),
                rs.getObject("start_date", LocalDate.class)) : null, ledgerId));
    }

    @Override
    public Optional<UUID> findAccount(UUID ledgerId, String code) {
        return Optional.ofNullable(jdbc.query(
                "select id from ledger_account where ledger_id = ? and code = ?",
                rs -> rs.next() ? rs.getObject(1, UUID.class) : null, ledgerId, code));
    }
}
