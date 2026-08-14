package com.example.accounting.voucher.internal.port;

import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherRequests;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public interface VoucherRepository {

    boolean reserveIdempotency(UUID ledgerId, UUID actorId, String key, String requestHash, UUID voucherId);

    Optional<Idempotency> findIdempotency(UUID ledgerId, UUID actorId, String key);

    String nextVoucherNumber(UUID ledgerId, UUID periodId, String voucherType);

    Optional<LedgerContext> findLedgerContext(UUID ledgerId, UUID periodId);

    List<LedgerContext> findLedgerContextsByDate(UUID ledgerId, LocalDate voucherDate);

    boolean activeAccountExists(UUID ledgerId, UUID accountId);

    Optional<AccountControls> accountControls(UUID ledgerId, UUID accountId);

    boolean validCashFlowItem(UUID ledgerId, UUID cashFlowItemId);

    boolean validDimensionValue(UUID ledgerId, UUID dimensionTypeId, UUID dimensionValueId);

    void createVoucher(UUID voucherId, UUID ledgerId, UUID periodId, LocalDate voucherDate, String voucherType,
                       String voucherNumber, String summary, boolean approvalRequired, UUID reversalOfId,
                       UUID actorId);

    void createGeneratedVoucher(UUID voucherId, UUID ledgerId, UUID periodId, LocalDate voucherDate,
                                String voucherType, String voucherNumber, String summary,
                                boolean approvalRequired, UUID reversalOfId, UUID actorId,
                                String sourceType, UUID sourceId);

    boolean updateVoucher(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate, String voucherType,
                          String voucherNumber, String summary, boolean approvalRequired, UUID actorId,
                          long expectedVersion);

    boolean replaceGeneratedVoucher(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate,
                                    String voucherType, String voucherNumber, String summary,
                                    boolean approvalRequired, UUID actorId, long expectedVersion,
                                    String sourceType, UUID expectedSourceId, UUID nextSourceId);

    void deleteLines(UUID ledgerId, UUID voucherId);

    void createLine(UUID lineId, UUID ledgerId, UUID voucherId, int lineNo, UUID accountId, String side,
                    String currency, BigDecimal originalAmount, BigDecimal exchangeRate, BigDecimal baseAmount,
                    String summary, UUID cashFlowItemId, BigDecimal quantity, BigDecimal unitPrice,
                    UUID dimensionCombinationId);

    void createLineDimensions(UUID lineId, UUID ledgerId, List<VoucherRequests.Dimension> dimensions);

    boolean controlsComplete(UUID ledgerId, UUID voucherId);

    /** Derives the internal business role from the voucher lines and same-period posted facts. */
    void reclassifyAccountingRole(UUID ledgerId, UUID voucherId);

    List<VoucherResponses.Voucher> list(UUID ledgerId, int limit, int offset);

    List<VoucherResponses.Voucher> list(UUID ledgerId, String periodCode, int limit, int offset);

    List<VoucherResponses.Voucher> list(UUID ledgerId, VoucherRequests.Search search, int limit, int offset);

    long count(UUID ledgerId, String periodCode);

    long count(UUID ledgerId, VoucherRequests.Search search);

    Optional<VoucherResponses.Voucher> find(UUID ledgerId, UUID voucherId, boolean includeDeleted);

    List<VoucherResponses.Line> lines(UUID ledgerId, UUID voucherId);

    Map<UUID, List<VoucherResponses.Line>> linesByVoucher(UUID ledgerId, List<UUID> voucherIds);

    Optional<VoucherState> findState(UUID ledgerId, UUID voucherId, boolean deletedOnly);

    int lineCount(UUID ledgerId, UUID voucherId);

    BigDecimal total(UUID ledgerId, UUID voucherId, String side);

    boolean changeStatus(UUID ledgerId, UUID voucherId, String expected, String next, UUID actorId);

    boolean post(UUID ledgerId, UUID voucherId, String expectedStatus, UUID actorId);

    void recordApproval(UUID ledgerId, UUID voucherId, String action, String comment, UUID actorId);

    boolean deleteVoucher(UUID ledgerId, UUID voucherId, long expectedVersion);

    List<VoucherResponses.Revision> listRevisions(UUID ledgerId, UUID voucherId);

    Optional<String> findRevisionData(UUID ledgerId, UUID voucherId, int revision);

    void restoreHeader(UUID ledgerId, UUID voucherId, UUID periodId, LocalDate voucherDate, String voucherType,
                       String voucherNumber, String summary, boolean approvalRequired, UUID actorId);

    int currentRevision(UUID ledgerId, UUID voucherId);

    void recordRevision(UUID ledgerId, UUID voucherId, int revision, String action, UUID actorId, String reason,
                        String beforeData, String afterData);

    record Idempotency(String requestHash, UUID voucherId) {
    }

    record LedgerContext(UUID periodId, String baseCurrency, boolean approvalRequired, String status,
                         LocalDate startDate, LocalDate endDate) {
    }

    record VoucherState(String status, boolean approvalRequired, long version, String sourceType, UUID sourceId) {

        public boolean generated() {
            return sourceType != null;
        }
    }

    record AccountControls(boolean cashFlowRequired, UUID defaultCashFlowItemId,
                           boolean quantityEnabled, String unitName,
                           List<UUID> dimensionTypeIds) {
    }
}
