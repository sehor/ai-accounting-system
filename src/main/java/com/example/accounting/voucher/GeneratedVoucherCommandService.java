package com.example.accounting.voucher;

import java.util.UUID;

/**
 * Internal command boundary for source workflows that own generated vouchers.
 * Fixed-asset depreciation cancellation, disposal reversal, and period-closing reset must clear
 * their own business references before invoking this command.
 */
public interface GeneratedVoucherCommandService {

    void deleteGenerated(UUID actorId, UUID ledgerId, UUID voucherId, String sourceType, UUID sourceId,
                         long expectedVersion, String reason);
}
