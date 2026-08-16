package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.util.List;

/** Response models for the Chinese SME statutory statements. */
public final class StatutoryReportResponses {

    private StatutoryReportResponses() {
    }

    @Schema(name = "StatutoryStatement", requiredProperties = {"reportType", "templateCode",
            "standardCode", "standardVersion", "periodCode", "primaryColumn", "comparativeColumn",
            "groups", "checks"})
    public record Statement(
            String reportType,
            String templateCode,
            String standardCode,
            String standardVersion,
            String periodCode,
            String primaryColumn,
            String comparativeColumn,
            List<Group> groups,
            List<Check> checks) {
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
}
