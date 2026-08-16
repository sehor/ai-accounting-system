package com.example.accounting.voucher;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class VoucherResponses {

    private VoucherResponses() {
    }

    @Schema(requiredProperties = {"id", "ledgerId", "periodId", "voucherDate", "voucherType", "status",
            "approvalRequired", "version", "lines", "voucherNumber", "summary", "sourceType", "sourceId"})
    public record Voucher(UUID id, UUID ledgerId, UUID periodId, LocalDate voucherDate, String voucherType,
                          @Schema(nullable = true) String voucherNumber,
                          @Schema(nullable = true) String summary, String status, boolean approvalRequired,
                          long version, List<Line> lines, @Schema(nullable = true) String sourceType,
                          @Schema(nullable = true) UUID sourceId) {

        public Voucher(UUID id, UUID ledgerId, UUID periodId, LocalDate voucherDate, String voucherType,
                       String voucherNumber, String summary, String status, boolean approvalRequired,
                       long version, List<Line> lines) {
            this(id, ledgerId, periodId, voucherDate, voucherType, voucherNumber, summary, status,
                    approvalRequired, version, lines, null, null);
        }
    }

    @Schema(name = "VoucherLineResponse", requiredProperties = {"id", "lineNo", "accountId", "side",
            "currency", "originalAmount", "exchangeRate", "baseAmount", "dimensions", "summary",
            "cashFlowItemId", "quantity", "unitPrice"})
    public record Line(UUID id, int lineNo, UUID accountId, String side, String currency,
                       BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount,
                       @Schema(nullable = true) String summary,
                       @Schema(nullable = true) UUID cashFlowItemId,
                       @Schema(nullable = true) BigDecimal quantity,
                       @Schema(nullable = true) BigDecimal unitPrice,
                       List<Dimension> dimensions) {

        public Line(UUID id, int lineNo, UUID accountId, String side, String currency,
                    BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount, String summary) {
            this(id, lineNo, accountId, side, currency, originalAmount, exchangeRate,
                    baseAmount, summary, null, null, null, List.of());
        }
    }

    @Schema(name = "VoucherDimensionResponse", requiredProperties = {"dimensionTypeId", "dimensionValueId"})
    public record Dimension(UUID dimensionTypeId, UUID dimensionValueId) {
    }

    @Schema(name = "VoucherRevision", requiredProperties = {
            "id", "revision", "action", "actorId", "reason", "beforeData", "afterData", "createdAt"})
    public record Revision(UUID id, int revision, String action, UUID actorId,
                           @Schema(nullable = true) String reason,
                           @Schema(nullable = true) String beforeData,
                           @Schema(nullable = true) String afterData, OffsetDateTime createdAt) {
    }
}
