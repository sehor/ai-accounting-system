package com.example.accounting.ledger;

import java.util.UUID;

public interface LedgerAccessService {

    LedgerRole requireMembership(UUID actorId, UUID ledgerId);
}
