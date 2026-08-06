package com.example.accounting.fixedasset.internal.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;

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
        BigDecimal basis = asset.originalCost()
                .subtract(asset.residualAmount())
                .subtract(asset.impairmentAmount())
                .subtract(asset.accumulatedDepreciation());
        if (basis.signum() <= 0 || asset.usefulLifeMonths() <= asset.openingDepreciatedMonths()) {
            return BigDecimal.ZERO.setScale(2);
        }

        long elapsedEligibleMonths = ChronoUnit.MONTHS.between(serviceMonth, period);
        long currentDepreciatedMonths = Math.max(asset.openingDepreciatedMonths(), elapsedEligibleMonths);
        long remainingBefore = asset.usefulLifeMonths() - currentDepreciatedMonths;
        if (remainingBefore <= 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        BigDecimal regular = basis.divide(BigDecimal.valueOf(
                asset.usefulLifeMonths() - asset.openingDepreciatedMonths()), 2, RoundingMode.HALF_UP);
        if (remainingBefore == 1) {
            BigDecimal alreadyAllocated = regular.multiply(BigDecimal.valueOf(
                    asset.usefulLifeMonths() - asset.openingDepreciatedMonths() - 1));
            return basis.subtract(alreadyAllocated).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        }
        return regular;
    }

    public record Asset(LocalDate serviceDate, BigDecimal originalCost, BigDecimal residualAmount,
                        int usefulLifeMonths, BigDecimal accumulatedDepreciation,
                        int openingDepreciatedMonths, LocalDate disposalDate,
                        BigDecimal impairmentAmount) {
    }
}
