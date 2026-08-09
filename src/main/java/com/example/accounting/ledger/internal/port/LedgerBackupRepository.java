package com.example.accounting.ledger.internal.port;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface LedgerBackupRepository {

    String ledgerJson(UUID ledgerId);

    String rowsJson(String table, UUID ledgerId);

    void createLedger(UUID ledgerId, UUID actorId, String name, String description, String standardCode,
                      String standardVersion, String baseCurrency, LocalDate startDate,
                      boolean approvalEnabled, String separator, int level2Width,
                      int level3Width, int level4Width);

    void createOwner(UUID ledgerId, UUID actorId);

    Map<String, String> columns(String table);

    void insertRow(String table, LinkedHashMap<String, Object> values, Set<String> jsonColumns);
}
