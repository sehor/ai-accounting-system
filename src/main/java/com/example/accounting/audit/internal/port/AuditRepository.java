package com.example.accounting.audit.internal.port;

import com.example.accounting.audit.AuditResponses;
import java.util.List;
import java.util.UUID;
import java.time.OffsetDateTime;

public interface AuditRepository {

    List<AuditResponses.Entry> list(UUID ledgerId);

    List<AuditResponses.Entry> page(UUID ledgerId, int limit, OffsetDateTime createdAt, UUID id,
                                    String aggregateType, UUID aggregateId);
}
