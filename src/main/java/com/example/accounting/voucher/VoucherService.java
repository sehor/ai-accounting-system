package com.example.accounting.voucher;

import java.util.List;
import java.util.UUID;

public interface VoucherService {

    VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request);

    VoucherResponses.Voucher create(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                    String idempotencyKey);

    VoucherResponses.Voucher createGenerated(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                             String idempotencyKey, String sourceType, UUID sourceId);

    VoucherResponses.Voucher createAgentDraft(UUID actorId, UUID ledgerId, VoucherRequests.Create request,
                                              String idempotencyKey);

    VoucherResponses.Voucher update(UUID actorId, UUID ledgerId, UUID voucherId, VoucherRequests.Update request);

    VoucherResponses.Voucher replaceGenerated(UUID actorId, UUID ledgerId, UUID voucherId,
                                              VoucherRequests.Update request, String sourceType,
                                              UUID expectedSourceId, UUID nextSourceId);

    List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId);

    List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId, int limit, int offset);

    List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId, String periodCode, int limit, int offset);

    List<VoucherResponses.Voucher> list(UUID actorId, UUID ledgerId, VoucherRequests.Search search,
                                        int limit, int offset);

    long count(UUID actorId, UUID ledgerId, String periodCode);

    long count(UUID actorId, UUID ledgerId, VoucherRequests.Search search);

    VoucherResponses.Voucher find(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher validate(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher validateAgentDraft(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher submit(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher approve(UUID actorId, UUID ledgerId, UUID voucherId, String comment);

    VoucherResponses.Voucher reject(UUID actorId, UUID ledgerId, UUID voucherId, String comment);

    VoucherResponses.Voucher post(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher postAgentVoucher(UUID actorId, UUID ledgerId, UUID voucherId);

    void delete(UUID actorId, UUID ledgerId, UUID voucherId);

    List<VoucherResponses.Revision> listRevisions(UUID actorId, UUID ledgerId, UUID voucherId);

    VoucherResponses.Voucher restoreRevision(UUID actorId, UUID ledgerId, UUID voucherId, int revision);
}
