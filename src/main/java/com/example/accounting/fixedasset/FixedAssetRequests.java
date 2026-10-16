package com.example.accounting.fixedasset;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.media.Schema;
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
            @JsonProperty(required = false) UUID impairmentAccountId,
            @NotNull UUID clearingAccountId,
            @NotNull UUID disposalGainAccountId,
            @NotNull UUID disposalLossAccountId) {
    }

    public record CategoryPatch(
            @NotNull Long expectedVersion,
            @Size(max = 200) @JsonProperty(required = false) String name,
            @Min(1) @Max(1200) @JsonProperty(required = false) Integer usefulLifeMonths,
            @DecimalMin("0") @JsonProperty(required = false) BigDecimal residualRate,
            @JsonProperty(required = false) UUID assetAccountId,
            @JsonProperty(required = false) UUID accumulatedDepreciationAccountId,
            @JsonProperty(required = false) UUID depreciationExpenseAccountId,
            @JsonProperty(required = false) UUID impairmentAccountId,
            @JsonProperty(required = false) UUID clearingAccountId,
            @JsonProperty(required = false) UUID disposalGainAccountId,
            @JsonProperty(required = false) UUID disposalLossAccountId,
            @JsonProperty(required = false) String status) {
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
            @JsonProperty(required = false) UUID departmentValueId,
            @JsonProperty(required = false) UUID acquisitionVoucherId,
            @JsonProperty(required = false) UUID assetAccountId,
            @JsonProperty(required = false) UUID accumulatedDepreciationAccountId,
            @JsonProperty(required = false) UUID depreciationExpenseAccountId,
            @JsonProperty(required = false) UUID impairmentAccountId,
            @JsonProperty(required = false) UUID clearingAccountId,
            @JsonProperty(required = false) UUID disposalGainAccountId,
            @JsonProperty(required = false) UUID disposalLossAccountId,
            @Size(max = 2000) @JsonProperty(required = false) String note) {
    }

    public record AssetPatch(
            @NotNull Long expectedVersion,
            @Size(max = 200) @JsonProperty(required = false) String name,
            @DecimalMin("0.000001") @JsonProperty(required = false) BigDecimal quantity,
            @JsonProperty(required = false) LocalDate serviceDate,
            @DecimalMin("0.01") @JsonProperty(required = false) BigDecimal originalCost,
            @DecimalMin("0") @JsonProperty(required = false) BigDecimal inputTax,
            @Min(1) @Max(1200) @JsonProperty(required = false) Integer usefulLifeMonths,
            @DecimalMin("0") @JsonProperty(required = false) BigDecimal residualRate,
            @DecimalMin("0") @JsonProperty(required = false) BigDecimal impairmentAmount,
            @JsonProperty(required = false) UUID departmentValueId,
            @JsonProperty(required = false) UUID acquisitionVoucherId,
            @JsonProperty(required = false) UUID assetAccountId,
            @JsonProperty(required = false) UUID accumulatedDepreciationAccountId,
            @JsonProperty(required = false) UUID depreciationExpenseAccountId,
            @JsonProperty(required = false) UUID impairmentAccountId,
            @JsonProperty(required = false) UUID clearingAccountId,
            @JsonProperty(required = false) UUID disposalGainAccountId,
            @JsonProperty(required = false) UUID disposalLossAccountId,
            @JsonProperty(required = false) UUID changePeriodId,
            @Size(max = 1000) @JsonProperty(required = false) String reason,
            @Size(max = 2000) @JsonProperty(required = false) String note) {
    }

    public record DepreciationAction(@NotNull UUID periodId, String reason) {
    }

    @Schema(name = "FixedAssetDisposalRequest")
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
