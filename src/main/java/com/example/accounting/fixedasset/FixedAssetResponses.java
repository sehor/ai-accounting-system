package com.example.accounting.fixedasset;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class FixedAssetResponses {

    private FixedAssetResponses() {
    }

    @Schema(name = "FixedAssetCategory", requiredProperties = {
            "id", "ledgerId", "code", "name", "usefulLifeMonths", "residualRate", "assetAccountId",
            "accumulatedDepreciationAccountId", "depreciationExpenseAccountId", "impairmentAccountId",
            "clearingAccountId", "disposalGainAccountId", "disposalLossAccountId", "status", "version"})
    public record Category(UUID id, UUID ledgerId, String code, String name, int usefulLifeMonths,
                           BigDecimal residualRate, UUID assetAccountId,
                           UUID accumulatedDepreciationAccountId, UUID depreciationExpenseAccountId,
                           @Schema(nullable = true) UUID impairmentAccountId,
                           UUID clearingAccountId, UUID disposalGainAccountId,
                           UUID disposalLossAccountId, String status, long version) {
    }

    @Schema(name = "FixedAsset", requiredProperties = {
            "id", "ledgerId", "categoryId", "categoryCode", "categoryName", "code", "name", "status",
            "quantity", "serviceDate", "originalCost", "inputTax", "usefulLifeMonths", "residualRate",
            "residualAmount", "openingAccumulatedDepreciation", "openingDepreciatedMonths", "impairmentAmount",
            "currentDepreciation", "currentAccumulatedDepreciation", "endingAccumulatedDepreciation",
            "openingNetValue", "endingNetValue", "departmentValueId", "acquisitionVoucherId", "assetAccountId",
            "accumulatedDepreciationAccountId", "depreciationExpenseAccountId", "impairmentAccountId",
            "clearingAccountId", "disposalGainAccountId", "disposalLossAccountId", "disposalDate", "note", "version"})
    public record Asset(UUID id, UUID ledgerId, UUID categoryId, String categoryCode, String categoryName,
                        String code, String name, String status, BigDecimal quantity, LocalDate serviceDate,
                        BigDecimal originalCost, BigDecimal inputTax, int usefulLifeMonths,
                        BigDecimal residualRate, BigDecimal residualAmount,
                        BigDecimal openingAccumulatedDepreciation, int openingDepreciatedMonths,
                        BigDecimal impairmentAmount, BigDecimal currentDepreciation,
                        BigDecimal currentAccumulatedDepreciation, BigDecimal endingAccumulatedDepreciation,
                        BigDecimal openingNetValue, BigDecimal endingNetValue,
                        @Schema(nullable = true) UUID departmentValueId,
                        @Schema(nullable = true) UUID acquisitionVoucherId,
                        UUID assetAccountId, UUID accumulatedDepreciationAccountId,
                        UUID depreciationExpenseAccountId, @Schema(nullable = true) UUID impairmentAccountId,
                        UUID clearingAccountId,
                        UUID disposalGainAccountId, UUID disposalLossAccountId,
                        @Schema(nullable = true) LocalDate disposalDate,
                        @Schema(nullable = true) String note, long version) {
    }

    @Schema(name = "FixedAssetPage", requiredProperties = {"data", "page", "pageSize", "totalItems", "totalPages"})
    public record Page(List<Asset> data, int page, int pageSize, long totalItems, int totalPages) {
    }

    @Schema(name = "FixedAssetDepreciationPreviewLine",
            requiredProperties = {"assetId", "assetCode", "assetName", "amount", "status", "detail"})
    public record PreviewLine(UUID assetId, String assetCode, String assetName, BigDecimal amount,
                              String status, String detail) {
    }

    @Schema(name = "FixedAssetDepreciationPreview", requiredProperties = {
            "periodId", "periodCode", "totalAmount", "eligibleCount", "completedCount", "pendingCount",
            "readyToClose", "blockers", "lines"})
    public record DepreciationPreview(UUID periodId, String periodCode, BigDecimal totalAmount,
                                      int eligibleCount, int completedCount, int pendingCount,
                                      boolean readyToClose, List<String> blockers, List<PreviewLine> lines) {
    }

    @Schema(name = "FixedAssetDepreciationRun", requiredProperties = {
            "id", "periodId", "runType", "status", "voucherId", "totalAmount", "inputFingerprint", "createdAt"})
    public record DepreciationRun(UUID id, UUID periodId, String runType, String status,
                                  @Schema(nullable = true) UUID voucherId,
                                  BigDecimal totalAmount, String inputFingerprint, OffsetDateTime createdAt) {
    }

    @Schema(name = "FixedAssetDisposalResponse", requiredProperties = {
            "id", "assetId", "periodId", "depreciationVoucherId", "transferVoucherId",
            "settlementVoucherId", "carryingAmount", "gainOrLoss"})
    public record Disposal(UUID id, UUID assetId, UUID periodId,
                           @Schema(nullable = true) UUID depreciationVoucherId,
                           @Schema(nullable = true) UUID transferVoucherId,
                           @Schema(nullable = true) UUID settlementVoucherId, BigDecimal carryingAmount,
                           BigDecimal gainOrLoss) {
    }

    @Schema(name = "FixedAssetImportResult",
            requiredProperties = {"rowCount", "errorCount", "committed", "errors"})
    public record ImportResult(int rowCount, int errorCount, boolean committed, List<String> errors) {
    }
}
