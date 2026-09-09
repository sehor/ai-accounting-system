package com.example.accounting.voucher;

import java.util.UUID;

public interface VoucherRenumberService {
    VoucherRenumbering.Preview preview(UUID actorId, UUID ledgerId, VoucherRenumbering.Request request);
    VoucherRenumbering.Result execute(UUID actorId, UUID ledgerId, VoucherRenumbering.ExecuteRequest request,
                                      String idempotencyKey);
}
