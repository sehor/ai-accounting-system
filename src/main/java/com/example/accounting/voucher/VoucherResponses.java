package com.example.accounting.voucher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VoucherResponses {

    private VoucherResponses() {
    }

    public record Voucher(UUID id, UUID ledgerId, UUID periodId, LocalDate voucherDate, String voucherType,
                          String voucherNumber, String summary, String status, boolean approvalRequired,
                          long version, List<Line> lines) {
    }

    public record Line(UUID id, int lineNo, UUID accountId, String side, String currency,
                       BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount, String summary) {
    }

    public record Revision(UUID id, int revision, String action, UUID actorId, String reason,
                           String beforeData, String afterData, OffsetDateTime createdAt) {
    }
}
