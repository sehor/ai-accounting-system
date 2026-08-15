package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

public final class FinanceQueryRequests {

    private FinanceQueryRequests() {
    }

    @Schema(name = "FinanceQueryRequest")
    public record Query(@NotBlank @Pattern(regexp = "DEBIT|CREDIT|NET|BALANCE") String metric,
                        String periodFrom,
                        String periodTo,
                        @NotEmpty List<@Pattern(regexp = "ACCOUNT|MONTH|CURRENCY|DIMENSION") String> groupBy,
                        @Valid Filters filters,
                        List<UUID> dimensionGroupTypeIds) {

        public Query {
            dimensionGroupTypeIds = dimensionGroupTypeIds == null ? List.of() : List.copyOf(dimensionGroupTypeIds);
        }

        public Query(String metric, String periodFrom, String periodTo, List<String> groupBy, Filters filters) {
            this(metric, periodFrom, periodTo, groupBy, filters, List.of());
        }
    }

    public record Filters(List<String> accountCodes, @Pattern(regexp = "[A-Z]{3}") String currency,
                          List<@Valid DimensionValue> dimensionValues) {

        public Filters {
            accountCodes = accountCodes == null ? List.of() : List.copyOf(accountCodes);
            dimensionValues = dimensionValues == null ? List.of() : List.copyOf(dimensionValues);
        }

        public Filters(List<String> accountCodes, String currency) {
            this(accountCodes, currency, List.of());
        }
    }

    @Schema(name = "FinanceQueryDimensionValue")
    public record DimensionValue(UUID dimensionTypeId, UUID dimensionValueId) {
    }
}
