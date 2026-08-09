package com.example.accounting.fixedasset.internal.port;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FixedAssetRepository {

    List<CategoryRecord> listCategories(UUID ledgerId);

    Optional<CategoryRecord> findCategory(UUID ledgerId, UUID categoryId);

    void insertCategory(CategoryRecord category, UUID actorId);

    boolean updateCategory(UUID ledgerId, UUID categoryId, CategoryRecord category, long expectedVersion, UUID actorId);

    List<AssetRecord> listAssets(UUID ledgerId, String status, UUID categoryId, UUID departmentValueId,
                                 String search, int limit, int offset);

    long countAssets(UUID ledgerId, String status, UUID categoryId, UUID departmentValueId, String search);

    Optional<AssetRecord> findAsset(UUID ledgerId, UUID assetId);

    boolean assetCodeExists(UUID ledgerId, String code);

    void insertAsset(AssetRecord asset, UUID actorId);

    boolean updateAsset(UUID ledgerId, UUID assetId, AssetRecord asset, long expectedVersion, UUID actorId);

    void softDeleteAsset(UUID ledgerId, UUID assetId, UUID actorId);

    boolean hasAssetUsage(UUID ledgerId, UUID assetId);

    List<AssetRecord> activeAssets(UUID ledgerId);

    void insertChange(UUID ledgerId, UUID assetId, UUID periodId, String reason, UUID actorId,
                      String beforeData, String afterData);

    Optional<RunRecord> currentRun(UUID ledgerId, UUID periodId, String runType);

    List<RunRecord> listRuns(UUID ledgerId, UUID periodId);

    List<LineRecord> activeLines(UUID ledgerId, UUID periodId);

    boolean hasActiveLine(UUID ledgerId, UUID assetId, UUID periodId);

    BigDecimal postedDepreciationBefore(UUID ledgerId, UUID assetId, UUID periodStartPeriodId);

    BigDecimal periodDepreciation(UUID ledgerId, UUID assetId, UUID periodId);

    void insertRun(RunRecord run, UUID actorId);

    void insertLine(LineRecord line);

    void supersedeRun(UUID ledgerId, UUID runId, UUID supersededBy);

    void supersedeLines(UUID ledgerId, UUID runId);

    void insertDisposal(DisposalRecord disposal, UUID actorId);

    boolean hasDisposal(UUID ledgerId, UUID assetId);

    record CategoryRecord(UUID id, UUID ledgerId, String code, String name, int usefulLifeMonths,
                          BigDecimal residualRate, UUID assetAccountId, UUID accumulatedDepreciationAccountId,
                          UUID depreciationExpenseAccountId, UUID impairmentAccountId, UUID clearingAccountId,
                          UUID disposalGainAccountId, UUID disposalLossAccountId, String status, long version) {
    }

    record AssetRecord(UUID id, UUID ledgerId, UUID categoryId, String categoryCode, String categoryName,
                       String code, String name, String status, BigDecimal quantity, LocalDate serviceDate,
                       BigDecimal originalCost, BigDecimal inputTax, int usefulLifeMonths,
                       BigDecimal residualRate, BigDecimal openingAccumulatedDepreciation,
                       int openingDepreciatedMonths, BigDecimal impairmentAmount, UUID departmentValueId,
                       UUID acquisitionVoucherId, UUID assetAccountId, UUID accumulatedDepreciationAccountId,
                       UUID depreciationExpenseAccountId, UUID impairmentAccountId, UUID clearingAccountId,
                       UUID disposalGainAccountId, UUID disposalLossAccountId, LocalDate disposalDate,
                       String note, long version) {
    }

    record RunRecord(UUID id, UUID ledgerId, UUID periodId, String runType, String status, UUID voucherId,
                     String inputFingerprint, BigDecimal totalAmount, String reason, UUID supersededBy,
                     OffsetDateTime createdAt) {
    }

    record LineRecord(UUID id, UUID ledgerId, UUID runId, UUID assetId, UUID periodId, BigDecimal amount,
                      UUID expenseAccountId, UUID accumulatedAccountId, UUID departmentValueId,
                      UUID voucherLineId, String status) {
    }

    record DisposalRecord(UUID id, UUID ledgerId, UUID assetId, UUID periodId, LocalDate disposalDate,
                          String reason, BigDecimal proceeds, BigDecimal outputTax, BigDecimal clearingCost,
                          BigDecimal clearingInputTax, UUID receiptAccountId, UUID paymentAccountId,
                          UUID outputTaxAccountId, UUID inputTaxAccountId, UUID depreciationVoucherId,
                          UUID transferVoucherId, UUID settlementVoucherId, BigDecimal carryingAmount,
                          BigDecimal gainOrLoss) {
    }
}
