package com.example.accounting.reporting.formula;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Unified per-account amount source for formula evaluation.  Only leaf accounts
 * are returned, including disabled accounts that still carry historical amounts.
 */
public record FormulaAccountAmount(
        UUID accountId,
        String code,
        String name,
        String standardAccountKey,
        String category,
        BigDecimal openingDebit,
        BigDecimal openingCredit,
        BigDecimal periodDebit,
        BigDecimal periodCredit,
        BigDecimal closingDebit,
        BigDecimal closingCredit) {
}
