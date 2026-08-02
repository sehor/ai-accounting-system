package com.example.accounting.documents.internal.port;

import com.example.accounting.documents.ExtractionResponses;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ExtractionRepository {

    void create(UUID extractionId, UUID ledgerId, UUID documentId, DocumentExtractor.Result result,
                String inputHash, String outputHash);

    List<ExtractionResponses.Extraction> list(UUID ledgerId, UUID documentId);

    Optional<OpenPeriod> firstOpenPeriod(UUID ledgerId);

    Optional<UUID> findAccount(UUID ledgerId, String code);

    record OpenPeriod(UUID id, LocalDate startDate) {
    }
}
