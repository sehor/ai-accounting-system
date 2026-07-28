package com.example.accounting.audit.internal.port;

import com.example.accounting.audit.AuditResponses;
import java.util.List;
import java.util.UUID;

public interface AuditRepository {

    List<AuditResponses.Entry> list(UUID ledgerId);
}
