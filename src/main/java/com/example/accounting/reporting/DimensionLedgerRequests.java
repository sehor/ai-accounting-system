package com.example.accounting.reporting;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.UUID;

/** Input contract for the immutable-combination auxiliary ledger. */
public final class DimensionLedgerRequests {

    private DimensionLedgerRequests() {
    }

    @Schema(name = "DimensionLedgerQuery")
    public record Query(String periodFrom,
                        String periodTo,
                        @NotNull UUID accountId,
                        @Pattern(regexp = "[A-Z]{3}") String currency,
                        List<@Valid DimensionValue> dimensionValues,
                        List<UUID> groupDimensionTypeIds,
                        Integer page,
                        Integer pageSize) {

        public Query {
            dimensionValues = dimensionValues == null ? List.of() : List.copyOf(dimensionValues);
            groupDimensionTypeIds = groupDimensionTypeIds == null ? List.of() : List.copyOf(groupDimensionTypeIds);
            page = page == null ? 1 : page;
            pageSize = pageSize == null ? 50 : pageSize;
        }
    }

    @Schema(name = "DimensionLedgerDimensionValue")
    public record DimensionValue(UUID dimensionTypeId, UUID dimensionValueId) {
    }
}
