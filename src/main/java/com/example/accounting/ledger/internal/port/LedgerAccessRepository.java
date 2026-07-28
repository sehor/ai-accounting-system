package com.example.accounting.ledger.internal.port;

import com.example.accounting.ledger.LedgerRole;
import java.util.Optional;
import java.util.UUID;

public interface LedgerAccessRepository {

    Optional<LedgerRole> findRole(UUID actorId, UUID ledgerId);
}
