package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Response models for the Chinese SME statutory statements. */
public final class StatutoryReportResponses {

    private StatutoryReportResponses() {
    }

    @Schema(name = "StatutoryStatement", requiredProperties = {"reportType", "templateCode",
            "standardCode", "standardVersion", "periodCode", "primaryColumn", "comparativeColumn",
            "groups", "checks", "dataQuality"})
    public record Statement(
            String reportType,
            String templateCode,
            String standardCode,
            String standardVersion,
            String periodCode,
            String primaryColumn,
            String comparativeColumn,
            List<Group> groups,
            List<Check> checks,
            @Schema(nullable = true) String formulaCode,
            @Schema(nullable = true) Integer formulaVersion,
            DataQuality dataQuality) {

        /** Compatibility constructor retained for pre-formula-editor call sites. */
        public Statement(String reportType, String templateCode, String standardCode,
                         String standardVersion, String periodCode, String primaryColumn,
                         String comparativeColumn, List<Group> groups, List<Check> checks) {
            this(reportType, templateCode, standardCode, standardVersion, periodCode,
                    primaryColumn, comparativeColumn, groups, checks, null, null,
                    DataQuality.complete());
        }

        /** Compatibility constructor for call sites that add formula metadata without quality data. */
        public Statement(String reportType, String templateCode, String standardCode,
                         String standardVersion, String periodCode, String primaryColumn,
                         String comparativeColumn, List<Group> groups, List<Check> checks,
                         String formulaCode, Integer formulaVersion) {
            this(reportType, templateCode, standardCode, standardVersion, periodCode,
                    primaryColumn, comparativeColumn, groups, checks, formulaCode,
                    formulaVersion, DataQuality.complete());
        }
    }

    @Schema(requiredProperties = {"key", "title", "lines"})
    public record Group(String key, String title, List<Line> lines) {
    }

    @Schema(name = "StatutoryStatementLine", requiredProperties = {"key", "lineNo", "name", "indent",
            "rowType", "primaryAmount", "comparativeAmount"})
    public record Line(
            String key,
            int lineNo,
            String name,
            int indent,
            String rowType,
            BigDecimal primaryAmount,
            BigDecimal comparativeAmount) {
    }

    @Schema(requiredProperties = {"key", "name", "passed", "difference"})
    public record Check(String key, String name, boolean passed, BigDecimal difference) {
    }

    /**
     * Data completeness of the statutory statement.  Balance sheet and income
     * statement always return {@code COMPLETE} with zero counts and no samples;
     * the cash flow statement reports unclassified external cash lines per
     * column and up to ten located samples.
     */
    @Schema(requiredProperties = {"status", "primaryUnclassifiedVoucherCount",
            "primaryUnclassifiedLineCount", "comparativeUnclassifiedVoucherCount",
            "comparativeUnclassifiedLineCount", "samples"})
    public record DataQuality(
            String status,
            int primaryUnclassifiedVoucherCount,
            int primaryUnclassifiedLineCount,
            int comparativeUnclassifiedVoucherCount,
            int comparativeUnclassifiedLineCount,
            List<QualitySample> samples) {

        public DataQuality {
            samples = samples == null ? List.of() : List.copyOf(samples);
        }

        public static DataQuality complete() {
            return new DataQuality("COMPLETE", 0, 0, 0, 0, List.of());
        }
    }

    @Schema(requiredProperties = {"voucherId", "voucherNumber", "periodCode", "voucherDate",
            "lineNo", "side", "baseAmount", "reason"})
    public record QualitySample(
            UUID voucherId,
            String voucherNumber,
            String periodCode,
            LocalDate voucherDate,
            int lineNo,
            String side,
            BigDecimal baseAmount,
            String reason) {
    }
}
