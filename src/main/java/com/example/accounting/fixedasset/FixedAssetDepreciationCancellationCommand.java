package com.example.accounting.fixedasset;

import java.util.UUID;

/** Capability boundary for atomically cancelling a fixed-asset depreciation run and its source voucher. */
public interface FixedAssetDepreciationCancellationCommand {

    FixedAssetResponses.DepreciationRun cancelDepreciation(
            UUID actorId, UUID ledgerId, UUID runId, String reason);
}
