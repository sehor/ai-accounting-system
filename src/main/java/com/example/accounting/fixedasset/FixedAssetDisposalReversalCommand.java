package com.example.accounting.fixedasset;

import java.util.UUID;

/** Capability boundary for reversing a complete fixed-asset disposal workflow. */
public interface FixedAssetDisposalReversalCommand {

    FixedAssetResponses.Asset cancelDisposal(
            UUID actorId, UUID ledgerId, UUID assetId, long expectedVersion, String reason);
}
