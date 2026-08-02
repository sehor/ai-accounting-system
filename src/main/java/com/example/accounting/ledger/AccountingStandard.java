package com.example.accounting.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDate;
import java.util.List;

public final class AccountingStandard {

    private AccountingStandard() {
    }

    public record Package(
            String code,
            String version,
            String name,
            LocalDate effectiveDate,
            AccountCodeRule accountCodeRule,
            List<Account> accounts,
            List<Formula> formulas,
            List<CashFlowItem> cashFlowItems,
            List<DimensionType> dimensionTypes) {

        public String key() {
            return code + "/" + version;
        }
    }

    public record Account(
            String code,
            String name,
            String parentCode,
            String category,
            String normalBalance,
            boolean cashFlowRequired,
            boolean quantityEnabled,
            String unitName) {
    }

    public record Formula(String code, String name, JsonNode definition) {
    }

    public record CashFlowItem(String code, String name) {
    }

    public record DimensionType(String code, String name, boolean required) {
    }
}
