package com.example.accounting.audit.internal.application;

import com.example.accounting.audit.AuditResponses;
import com.example.accounting.audit.AuditService;
import com.example.accounting.audit.internal.port.AuditRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DefaultAuditService implements AuditService {

    private static final Set<LedgerRole> VIEW_ROLES = Set.of(
            LedgerRole.OWNER, LedgerRole.EDITOR, LedgerRole.REVIEWER, LedgerRole.VIEWER, LedgerRole.AGENT);

    private final LedgerAccessService ledgerAccess;
    private final AuditRepository audits;

    public DefaultAuditService(LedgerAccessService ledgerAccess, AuditRepository audits) {
        this.ledgerAccess = ledgerAccess;
        this.audits = audits;
    }

    @Override
    @Transactional(readOnly = true)
    public List<AuditResponses.Entry> list(UUID actorId, UUID ledgerId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw new ApiProblemException(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view audit records", false);
        }
        return audits.list(ledgerId);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditResponses.Page page(UUID actorId, UUID ledgerId, int limit, String cursor,
                                    String aggregateType, UUID aggregateId) {
        if (!VIEW_ROLES.contains(ledgerAccess.requireMembership(actorId, ledgerId))) {
            throw new ApiProblemException(403, "INSUFFICIENT_LEDGER_ROLE", "Insufficient ledger role",
                    "The current user cannot view audit records", false);
        }
        if (limit < 1 || limit > 200) {
            throw new ApiProblemException(422, "AUDIT_LIMIT_INVALID", "Invalid audit page size",
                    "limit must be between 1 and 200", false);
        }
        Cursor decoded = decodeCursor(cursor);
        String normalizedType = aggregateType == null || aggregateType.isBlank() ? null : aggregateType.trim();
        List<AuditResponses.Entry> rows = audits.page(ledgerId, limit + 1, decoded.createdAt(), decoded.id(),
                normalizedType, aggregateId);
        boolean hasMore = rows.size() > limit;
        if (hasMore) {
            rows = rows.subList(0, limit);
        }
        String next = hasMore && !rows.isEmpty() ? encodeCursor(rows.getLast()) : null;
        return new AuditResponses.Page(List.copyOf(rows), next, hasMore);
    }

    private Cursor decodeCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return new Cursor(null, null);
        }
        try {
            String value = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = value.split("\\|", -1);
            if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                throw new IllegalArgumentException();
            }
            return new Cursor(OffsetDateTime.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw new ApiProblemException(422, "AUDIT_CURSOR_INVALID", "Invalid audit cursor",
                    "cursor must be an opaque cursor returned by the audit endpoint", false);
        }
    }

    private String encodeCursor(AuditResponses.Entry entry) {
        String value = entry.createdAt().toString() + "|" + entry.id();
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private record Cursor(OffsetDateTime createdAt, UUID id) {
    }
}
