package com.example.accounting.reporting;

import com.example.accounting.shared.web.ApiProblemException;

/** Canonical inclusive accounting-period range used by every balance-backed query. */
public record PeriodRange(String periodFrom, String periodTo) {

    private static final String PERIOD_PATTERN = "\\d{4}-(0[1-9]|1[0-2])";

    public PeriodRange {
        if (periodFrom == null || periodTo == null
                || !periodFrom.matches(PERIOD_PATTERN) || !periodTo.matches(PERIOD_PATTERN)
                || periodFrom.compareTo(periodTo) > 0) {
            throw invalid("periodFrom and periodTo must be valid YYYY-MM values in ascending order");
        }
    }

    public static PeriodRange normalize(String periodCode, String periodFrom, String periodTo) {
        boolean hasCode = periodCode != null && !periodCode.isBlank();
        boolean hasFrom = periodFrom != null && !periodFrom.isBlank();
        boolean hasTo = periodTo != null && !periodTo.isBlank();
        if (hasCode && (hasFrom || hasTo)) {
            throw invalid("periodCode cannot be combined with periodFrom or periodTo");
        }
        if (hasFrom != hasTo) {
            throw invalid("periodFrom and periodTo must be provided together");
        }
        if (hasCode) {
            return new PeriodRange(periodCode, periodCode);
        }
        if (hasFrom) {
            return new PeriodRange(periodFrom, periodTo);
        }
        throw invalid("Provide periodCode or both periodFrom and periodTo");
    }

    public static PeriodRange single(String periodCode) {
        return normalize(periodCode, null, null);
    }

    public boolean singlePeriod() {
        return periodFrom.equals(periodTo);
    }

    public String periodCode() {
        return singlePeriod() ? periodFrom : null;
    }

    private static ApiProblemException invalid(String detail) {
        return new ApiProblemException(400, "PERIOD_RANGE_INVALID", "Invalid period range", detail, false);
    }
}
