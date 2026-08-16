package com.example.accounting.reporting.formula;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Per-item debit and credit totals of posted external cash movements within a
 * period range.  Internal cash-to-cash transfers are excluded by the query;
 * item codes absent from the data contribute zero amounts.  Red and reversal
 * entries keep the sign of {@code base_amount}.
 */
public record CashFlowSource(Map<String, BigDecimal> debit, Map<String, BigDecimal> credit) {

    public CashFlowSource {
        debit = Map.copyOf(debit);
        credit = Map.copyOf(credit);
    }

    public static CashFlowSource empty() {
        return new CashFlowSource(Map.of(), Map.of());
    }

    public static CashFlowSource of(Map<String, BigDecimal> debit, Map<String, BigDecimal> credit) {
        return new CashFlowSource(debit, credit);
    }

    /** Merges another source by summing per-code totals (used for column-wide deduplication). */
    public CashFlowSource merge(CashFlowSource other) {
        Map<String, BigDecimal> mergedDebit = new LinkedHashMap<>(debit);
        other.debit().forEach((code, amount) ->
                mergedDebit.merge(code, amount, BigDecimal::add));
        Map<String, BigDecimal> mergedCredit = new LinkedHashMap<>(credit);
        other.credit().forEach((code, amount) ->
                mergedCredit.merge(code, amount, BigDecimal::add));
        return new CashFlowSource(mergedDebit, mergedCredit);
    }
}
