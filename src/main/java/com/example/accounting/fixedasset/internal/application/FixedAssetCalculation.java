package com.example.accounting.fixedasset.internal.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;

/** Pure monthly depreciation rules shared by previews and posting. */
public final class FixedAssetCalculation {

    private FixedAssetCalculation() {
    }

    public static BigDecimal monthly(Asset asset, LocalDate periodEnd) {
        YearMonth period = YearMonth.from(periodEnd);
        YearMonth serviceMonth = YearMonth.from(asset.serviceDate());
        if (!period.isAfter(serviceMonth)) {
            return BigDecimal.ZERO.setScale(2);
        }
        if (asset.disposalDate() != null && period.isAfter(YearMonth.from(asset.disposalDate()))) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal depreciableAmount = asset.originalCost()
                .subtract(asset.residualAmount())
                .subtract(asset.impairmentAmount());
        BigDecimal openingBasis = depreciableAmount.subtract(asset.openingAccumulatedDepreciation());
        BigDecimal remainingAmount = depreciableAmount.subtract(asset.accumulatedDepreciation());
        int openingRemainingMonths = asset.usefulLifeMonths() - asset.openingDepreciatedMonths();
        int remainingMonths = asset.usefulLifeMonths() - asset.depreciatedMonths();
        if (openingBasis.signum() <= 0 || remainingAmount.signum() <= 0
                || openingRemainingMonths <= 0 || remainingMonths <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }

        BigDecimal regular = openingBasis.divide(
                BigDecimal.valueOf(openingRemainingMonths), 2, RoundingMode.HALF_UP);
        if (remainingMonths == 1) {
            return remainingAmount.setScale(2, RoundingMode.HALF_UP);
        }
        return regular.min(remainingAmount).setScale(2, RoundingMode.HALF_UP);
    }

    public record Asset(LocalDate serviceDate, BigDecimal originalCost, BigDecimal residualAmount,
                        int usefulLifeMonths, BigDecimal openingAccumulatedDepreciation,
                        int openingDepreciatedMonths, BigDecimal accumulatedDepreciation,
                        int depreciatedMonths, LocalDate disposalDate,
                        BigDecimal impairmentAmount) {
    }
}
