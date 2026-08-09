package com.example.accounting.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.fixedasset.internal.application.FixedAssetCalculation;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FixedAssetCalculationTest {

    @Test
    void startsDepreciationTheMonthAfterInService() {
        FixedAssetCalculation.Asset asset = asset("2026-06-15", "1200", "0", 12, "0", 0, "0");

        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2026, 6, 30)))
                .isEqualByComparingTo("0.00");
        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2026, 7, 31)))
                .isEqualByComparingTo("100.00");
    }

    @Test
    void historicalAssetUsesRemainingBasisAndRoundsFinalMonthToTheResidual() {
        FixedAssetCalculation.Asset asset = asset("2025-01-01", "1000", "100", 12, "450", 5, "0");

        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2025, 6, 30)))
                .isEqualByComparingTo("64.29");
        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2025, 12, 31)))
                .isEqualByComparingTo("64.26");
        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2026, 1, 31)))
                .isEqualByComparingTo("0.00");
    }

    @Test
    void disposalMonthStillDepreciates() {
        FixedAssetCalculation.Asset asset = asset("2026-01-01", "1200", "0", 12, "0", 0, "2026-06-10");

        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2026, 6, 30)))
                .isEqualByComparingTo("100.00");
        assertThat(FixedAssetCalculation.monthly(asset, LocalDate.of(2026, 7, 31)))
                .isEqualByComparingTo("0.00");
    }

    private FixedAssetCalculation.Asset asset(String serviceDate, String cost, String residual, int months,
                                               String accumulated, int usedMonths, String disposalDate) {
        return new FixedAssetCalculation.Asset(
                LocalDate.parse(serviceDate), new BigDecimal(cost), new BigDecimal(residual), months,
                new BigDecimal(accumulated), usedMonths,
                disposalDate.startsWith("2026") ? LocalDate.parse(disposalDate) : null,
                new BigDecimal("0"));
    }
}
