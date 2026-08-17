package com.example.accounting.reporting.internal.application;

import com.example.accounting.reporting.formula.FormulaAccountAmount;
import java.math.BigDecimal;
import java.util.List;

/** Pure DERIVED_V1 account-key aggregation; it never reads voucher classifications. */
final class DerivedCashFlowCalculator {
    private DerivedCashFlowCalculator() { }

    static BigDecimal debit(List<FormulaAccountAmount> amounts, String... keys) {
        return sum(amounts, true, false, keys);
    }

    static BigDecimal credit(List<FormulaAccountAmount> amounts, String... keys) {
        return sum(amounts, false, false, keys);
    }

    static BigDecimal debitIncrease(List<FormulaAccountAmount> amounts, String... keys) {
        return sum(amounts, true, true, keys);
    }

    static BigDecimal creditIncrease(List<FormulaAccountAmount> amounts, String... keys) {
        return sum(amounts, false, true, keys);
    }

    static BigDecimal openingNet(List<FormulaAccountAmount> amounts, String... keys) {
        return balance(amounts, true, keys);
    }

    static BigDecimal closingNet(List<FormulaAccountAmount> amounts, String... keys) {
        return balance(amounts, false, keys);
    }

    private static BigDecimal sum(List<FormulaAccountAmount> amounts, boolean debit, boolean balanceChange,
                                  String... keys) {
        return amounts.stream().filter(a -> matches(a.standardAccountKey(), keys)).map(a -> {
            if (!balanceChange) return debit ? a.periodDebit() : a.periodCredit();
            return debit ? a.closingDebit().subtract(a.openingDebit())
                    : a.closingCredit().subtract(a.openingCredit());
        }).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal balance(List<FormulaAccountAmount> amounts, boolean opening, String... keys) {
        return amounts.stream().filter(a -> matches(a.standardAccountKey(), keys))
                .map(a -> (opening ? a.openingDebit() : a.closingDebit())
                        .subtract(opening ? a.openingCredit() : a.closingCredit()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean matches(String key, String... expected) {
        if (key == null) return false;
        for (String value : expected) if (key.equals(value)) return true;
        return false;
    }
}
