package com.example.accounting.reporting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import java.util.List;

public final class FinanceQueryRequests {

    private FinanceQueryRequests() {
    }

    public record Query(@NotBlank @Pattern(regexp = "DEBIT|CREDIT|NET|BALANCE") String metric,
                        String periodFrom,
                        String periodTo,
                        @NotEmpty List<@Pattern(regexp = "ACCOUNT|MONTH|CURRENCY|DIMENSION") String> groupBy,
                        @Valid Filters filters) {
    }

    public record Filters(List<String> accountCodes, @Pattern(regexp = "[A-Z]{3}") String currency) {
    }
}
