package com.example.accounting.reporting;

import java.math.BigDecimal;
import java.util.List;

/** Response models for the Chinese SME statutory statements. */
public final class StatutoryReportResponses {

    private StatutoryReportResponses() {
    }

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

    public record Group(String key, String title, List<Line> lines) {
    }

    public record Line(
            String key,
            int lineNo,
            String name,
            int indent,
            String rowType,
            BigDecimal primaryAmount,
            BigDecimal comparativeAmount) {
    }

    public record Check(String key, String name, boolean passed, BigDecimal difference) {
    }
}
