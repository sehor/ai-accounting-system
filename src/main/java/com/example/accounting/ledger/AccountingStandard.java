package com.example.accounting.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDate;
import java.util.List;

public final class AccountingStandard {

    private AccountingStandard() {
    }

    @Schema(name = "AccountingStandardPackage", requiredProperties = {"code", "version", "name",
            "effectiveDate", "accountCodeRule", "standardAccountKeys", "accounts", "formulas",
            "cashFlowItems", "dimensionTypes"})
    public record Package(
            String code,
            String version,
            String name,
            LocalDate effectiveDate,
            AccountCodeRule accountCodeRule,
            List<StandardAccountKey> standardAccountKeys,
            List<Account> accounts,
            List<Formula> formulas,
            List<CashFlowItem> cashFlowItems,
            List<DimensionType> dimensionTypes) {

        public String key() {
            return code + "/" + version;
        }
    }

    @Schema(name = "AccountingStandardAccount", requiredProperties = {"code", "standardAccountKey", "name",
            "parentCode", "category", "normalBalance", "cashFlowRequired", "quantityEnabled", "unitName"})
    public record Account(
            String code,
            String standardAccountKey,
            String name,
            @Schema(nullable = true) String parentCode,
            String category,
            String normalBalance,
            boolean cashFlowRequired,
            boolean quantityEnabled,
            @Schema(nullable = true) String unitName) {
    }

    @Schema(name = "AccountingStandardKey", requiredProperties = {"key", "legacyCodes"})
    public record StandardAccountKey(String key, List<String> legacyCodes) {
    }

    @Schema(name = "AccountingStandardFormula", requiredProperties = {"code", "name", "definition"})
    public record Formula(String code, String name, JsonNode definition) {
    }

    @Schema(name = "AccountingStandardCashFlowItem", requiredProperties = {"code", "name"})
    public record CashFlowItem(String code, String name) {
    }

    @Schema(name = "AccountingStandardDimensionType", requiredProperties = {"code", "name", "required"})
    public record DimensionType(String code, String name, boolean required) {
    }
}
