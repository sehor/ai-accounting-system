package com.example.accounting.fixedasset;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FixedAssetResponses {

    private FixedAssetResponses() {
    }

    public record Category(UUID id, UUID ledgerId, String code, String name, int usefulLifeMonths,
                           BigDecimal residualRate, UUID assetAccountId,
                           UUID accumulatedDepreciationAccountId, UUID depreciationExpenseAccountId,
                           UUID impairmentAccountId, UUID clearingAccountId, UUID disposalGainAccountId,
                           UUID disposalLossAccountId, String status, long version) {
    }

    public record Asset(UUID id, UUID ledgerId, UUID categoryId, String categoryCode, String categoryName,
                        String code, String name, String status, BigDecimal quantity, LocalDate serviceDate,
                        BigDecimal originalCost, BigDecimal inputTax, int usefulLifeMonths,
                        BigDecimal residualRate, BigDecimal residualAmount,
                        BigDecimal openingAccumulatedDepreciation, int openingDepreciatedMonths,
                        BigDecimal impairmentAmount, BigDecimal currentDepreciation,
                        BigDecimal currentAccumulatedDepreciation, BigDecimal endingAccumulatedDepreciation,
                        BigDecimal openingNetValue, BigDecimal endingNetValue, UUID departmentValueId,
                        UUID acquisitionVoucherId, UUID assetAccountId, UUID accumulatedDepreciationAccountId,
                        UUID depreciationExpenseAccountId, UUID impairmentAccountId, UUID clearingAccountId,
                        UUID disposalGainAccountId, UUID disposalLossAccountId, LocalDate disposalDate,
                        String note, long version) {
    }

    public record Page(List<Asset> data, int page, int pageSize, long totalItems, int totalPages) {
    }

    public record PreviewLine(UUID assetId, String assetCode, String assetName, BigDecimal amount,
                              String status, String detail) {
    }

    public record DepreciationPreview(UUID periodId, String periodCode, BigDecimal totalAmount,
                                      int eligibleCount, int completedCount, int pendingCount,
                                      boolean readyToClose, List<String> blockers, List<PreviewLine> lines) {
    }

    public record DepreciationRun(UUID id, UUID periodId, String runType, String status, UUID voucherId,
                                  BigDecimal totalAmount, String inputFingerprint, OffsetDateTime createdAt) {
    }

    public record Disposal(UUID id, UUID assetId, UUID periodId, UUID depreciationVoucherId,
                           UUID transferVoucherId, UUID settlementVoucherId, BigDecimal carryingAmount,
                           BigDecimal gainOrLoss) {
    }

    public record ImportResult(int rowCount, int errorCount, boolean committed, List<String> errors) {
    }
}
