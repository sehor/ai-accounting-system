package com.example.accounting.documents;

import com.example.accounting.voucher.VoucherResponses;
import java.util.List;
import java.util.UUID;

public interface ExtractionService {

    ExtractionResponses.Extraction extract(UUID actorId, UUID ledgerId, UUID documentId);

    List<ExtractionResponses.Extraction> list(UUID actorId, UUID ledgerId, UUID documentId);

    VoucherResponses.Voucher createVoucherDraft(UUID actorId, UUID ledgerId, UUID documentId);

    VoucherResponses.Voucher createAgentVoucherDraft(UUID actorId, UUID ledgerId, UUID documentId);
}
