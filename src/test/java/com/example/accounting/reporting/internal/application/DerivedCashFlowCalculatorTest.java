package com.example.accounting.reporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.reporting.formula.FormulaAccountAmount;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DerivedCashFlowCalculatorTest {

    @Test
    void aggregatesOccurrencesAndDirectionalBalanceChangesByStandardKey() {
        FormulaAccountAmount receivable = amount("ASSET.ACCOUNTS_RECEIVABLE", 100, 0, 0, 0, 125, 0);
        FormulaAccountAmount payable = amount("LIABILITY.ACCOUNTS_PAYABLE", 0, 80, 0, 0, 0, 65);
        FormulaAccountAmount tax = amount("LIABILITY.TAX_PAYABLE", 0, 0, 30, 12, 0, 0);

        assertThat(DerivedCashFlowCalculator.debitIncrease(List.of(receivable), "ASSET.ACCOUNTS_RECEIVABLE"))
                .isEqualByComparingTo("25");
        assertThat(DerivedCashFlowCalculator.creditIncrease(List.of(payable), "LIABILITY.ACCOUNTS_PAYABLE"))
                .isEqualByComparingTo("-15");
        assertThat(DerivedCashFlowCalculator.debit(List.of(tax), "LIABILITY.TAX_PAYABLE"))
                .isEqualByComparingTo("30");
        assertThat(DerivedCashFlowCalculator.credit(List.of(tax), "LIABILITY.TAX_PAYABLE"))
                .isEqualByComparingTo("12");
    }

    private static FormulaAccountAmount amount(String key, int openingDebit, int openingCredit,
                                                int periodDebit, int periodCredit,
                                                int closingDebit, int closingCredit) {
        return new FormulaAccountAmount(UUID.randomUUID(), "1001", "test", key, "CURRENT_ASSET",
                BigDecimal.valueOf(openingDebit), BigDecimal.valueOf(openingCredit),
                BigDecimal.valueOf(periodDebit), BigDecimal.valueOf(periodCredit),
                BigDecimal.valueOf(closingDebit), BigDecimal.valueOf(closingCredit));
    }
}
