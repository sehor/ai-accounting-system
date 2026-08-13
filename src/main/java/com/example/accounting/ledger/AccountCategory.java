package com.example.accounting.ledger;

import java.util.Locale;

/**
 * Fine-grained accounting account type.
 *
 * <p>The database and API store the enum name (for example {@code CURRENT_ASSET});
 * Chinese labels are used only for user-facing import/export and frontend display.
 */
public enum AccountCategory {

    CURRENT_ASSET("流动资产"),
    NON_CURRENT_ASSET("非流动资产"),
    CURRENT_LIABILITY("流动负债"),
    NON_CURRENT_LIABILITY("非流动负债"),
    EQUITY("所有者权益"),
    COST("成本"),
    OPERATING_REVENUE("营业收入"),
    OTHER_INCOME("其他收益"),
    OPERATING_COST_AND_TAX("营业成本及税金"),
    OTHER_EXPENSE("其他损失"),
    PERIOD_EXPENSE("期间费用"),
    INCOME_TAX("所得税"),
    PRIOR_YEAR_ADJUSTMENT("以前年度损益调整");

    private final String label;

    AccountCategory(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public String code() {
        return name();
    }

    public static boolean isValid(String value) {
        return value != null && fromCode(value) != null;
    }

    public static AccountCategory fromCode(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        for (AccountCategory category : values()) {
            if (category.name().equals(normalized)) {
                return category;
            }
        }
        return null;
    }
}
