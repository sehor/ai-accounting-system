package com.example.accounting.fixedasset;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public final class FixedAssetRequests {

    private FixedAssetRequests() {
    }

    public record CategoryCreate(
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @Min(1) @Max(1200) Integer usefulLifeMonths,
            @NotNull @DecimalMin("0") BigDecimal residualRate,
            @NotNull UUID assetAccountId,
            @NotNull UUID accumulatedDepreciationAccountId,
            @NotNull UUID depreciationExpenseAccountId,
            UUID impairmentAccountId,
            @NotNull UUID clearingAccountId,
            @NotNull UUID disposalGainAccountId,
            @NotNull UUID disposalLossAccountId) {
    }

    public record CategoryPatch(
            @NotNull Long expectedVersion,
            @Size(max = 200) String name,
            @Min(1) @Max(1200) Integer usefulLifeMonths,
            @DecimalMin("0") BigDecimal residualRate,
            UUID assetAccountId,
            UUID accumulatedDepreciationAccountId,
            UUID depreciationExpenseAccountId,
            UUID impairmentAccountId,
            UUID clearingAccountId,
            UUID disposalGainAccountId,
            UUID disposalLossAccountId,
            String status) {
    }

    public record AssetCreate(
            @NotNull UUID categoryId,
            @NotBlank @Size(max = 64) String code,
            @NotBlank @Size(max = 200) String name,
            @NotNull @DecimalMin("0.000001") BigDecimal quantity,
            @NotNull LocalDate serviceDate,
            @NotNull @DecimalMin("0.01") BigDecimal originalCost,
            @NotNull @DecimalMin("0") BigDecimal inputTax,
            @NotNull @Min(1) @Max(1200) Integer usefulLifeMonths,
            @NotNull @DecimalMin("0") BigDecimal residualRate,
            @NotNull @DecimalMin("0") BigDecimal openingAccumulatedDepreciation,
            @NotNull @Min(0) Integer openingDepreciatedMonths,
            @NotNull @DecimalMin("0") BigDecimal impairmentAmount,
            UUID departmentValueId,
            UUID acquisitionVoucherId,
            UUID assetAccountId,
            UUID accumulatedDepreciationAccountId,
            UUID depreciationExpenseAccountId,
            UUID impairmentAccountId,
            UUID clearingAccountId,
            UUID disposalGainAccountId,
            UUID disposalLossAccountId,
            @Size(max = 2000) String note) {
    }

    public record AssetPatch(
            @NotNull Long expectedVersion,
            @Size(max = 200) String name,
            @DecimalMin("0.000001") BigDecimal quantity,
            LocalDate serviceDate,
            @DecimalMin("0.01") BigDecimal originalCost,
            @DecimalMin("0") BigDecimal inputTax,
            @Min(1) @Max(1200) Integer usefulLifeMonths,
            @DecimalMin("0") BigDecimal residualRate,
            @DecimalMin("0") BigDecimal impairmentAmount,
            UUID departmentValueId,
            UUID acquisitionVoucherId,
            UUID assetAccountId,
            UUID accumulatedDepreciationAccountId,
            UUID depreciationExpenseAccountId,
            UUID impairmentAccountId,
            UUID clearingAccountId,
            UUID disposalGainAccountId,
            UUID disposalLossAccountId,
            UUID changePeriodId,
            @Size(max = 1000) String reason,
            @Size(max = 2000) String note) {
    }

    public record DepreciationAction(@NotNull UUID periodId, String reason) {
    }

    public record Disposal(
            @NotNull UUID periodId,
            @NotNull LocalDate disposalDate,
            @NotBlank @Size(max = 1000) String reason,
            @NotNull @DecimalMin("0") BigDecimal proceeds,
            @NotNull @DecimalMin("0") BigDecimal outputTax,
            @NotNull @DecimalMin("0") BigDecimal clearingCost,
            @NotNull @DecimalMin("0") BigDecimal clearingInputTax,
            UUID receiptAccountId,
            UUID paymentAccountId,
            UUID outputTaxAccountId,
            UUID inputTaxAccountId) {
    }

    public record DisposalCancellation(
            @NotBlank @Size(max = 1000) String reason,
            @NotNull Long expectedVersion) {
    }
}
