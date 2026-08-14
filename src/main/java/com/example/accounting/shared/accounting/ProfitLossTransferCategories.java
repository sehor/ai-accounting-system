package com.example.accounting.shared.accounting;

import java.util.Set;

/** Categories whose monthly balances are transferred to the current-year-profit account. */
public final class ProfitLossTransferCategories {

    private static final Set<String> REVENUE = Set.of("OPERATING_REVENUE", "OTHER_INCOME");
    private static final Set<String> EXPENSE = Set.of(
            "OPERATING_COST_AND_TAX", "OTHER_EXPENSE", "PERIOD_EXPENSE",
            "INCOME_TAX", "PRIOR_YEAR_ADJUSTMENT");
    private ProfitLossTransferCategories() {
    }

    public static Set<String> revenue() {
        return REVENUE;
    }

    public static Set<String> expense() {
        return EXPENSE;
    }

}
