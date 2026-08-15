package com.example.accounting.audit;

import java.util.List;
import java.util.UUID;

public interface AuditService {

    List<AuditResponses.Entry> list(UUID actorId, UUID ledgerId);

    AuditResponses.Page page(UUID actorId, UUID ledgerId, int limit, String cursor,
                             String aggregateType, UUID aggregateId);
}
