package com.example.accounting.fixedasset;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.web.multipart.MultipartFile;

public interface FixedAssetService {

    List<FixedAssetResponses.Category> listCategories(UUID actorId, UUID ledgerId);

    FixedAssetResponses.Category findCategory(UUID actorId, UUID ledgerId, UUID categoryId);

    FixedAssetResponses.Category createCategory(UUID actorId, UUID ledgerId,
                                                FixedAssetRequests.CategoryCreate request);

    FixedAssetResponses.Category updateCategory(UUID actorId, UUID ledgerId, UUID categoryId,
                                                FixedAssetRequests.CategoryPatch request);

    FixedAssetResponses.Page listAssets(UUID actorId, UUID ledgerId, UUID periodId, String status,
                                        UUID categoryId, UUID departmentValueId, String search,
                                        int page, int pageSize);

    FixedAssetResponses.Asset findAsset(UUID actorId, UUID ledgerId, UUID assetId, UUID periodId);

    FixedAssetResponses.Asset createAsset(UUID actorId, UUID ledgerId, FixedAssetRequests.AssetCreate request);

    FixedAssetResponses.Asset updateAsset(UUID actorId, UUID ledgerId, UUID assetId,
                                          FixedAssetRequests.AssetPatch request);

    FixedAssetResponses.Asset copyAsset(UUID actorId, UUID ledgerId, UUID assetId);

    void deleteAsset(UUID actorId, UUID ledgerId, UUID assetId);

    FixedAssetResponses.DepreciationPreview previewDepreciation(UUID actorId, UUID ledgerId, UUID periodId);

    FixedAssetResponses.DepreciationRun generateDepreciation(UUID actorId, UUID ledgerId,
                                                              FixedAssetRequests.DepreciationAction request);

    FixedAssetResponses.DepreciationRun regenerateDepreciation(UUID actorId, UUID ledgerId,
                                                                FixedAssetRequests.DepreciationAction request);

    List<FixedAssetResponses.DepreciationRun> listDepreciationRuns(UUID actorId, UUID ledgerId, UUID periodId);

    FixedAssetResponses.Disposal disposeAsset(UUID actorId, UUID ledgerId, UUID assetId,
                                              FixedAssetRequests.Disposal request);

    boolean periodComplete(UUID actorId, UUID ledgerId, UUID periodId);

    List<String> periodBlockers(UUID actorId, UUID ledgerId, UUID periodId);

    byte[] importTemplate(UUID actorId, UUID ledgerId);

    FixedAssetResponses.ImportResult importAssets(UUID actorId, UUID ledgerId, MultipartFile file);
}
