package com.example.accounting.reporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.reporting.StatutoryReportResponses;
import com.example.accounting.reporting.internal.port.ReportingRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatutoryReportCalculatorTest {

    private final StatutoryReportCalculator calculator = new StatutoryReportCalculator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void calculatesProfitRowsFromStableKeys() throws Exception {
        List<ReportingRepository.StatutoryAccountAmount> ytd = List.of(
                line("INCOME.MAIN_BUSINESS_REVENUE", "0", "150"),
                line("EXPENSE.MAIN_BUSINESS_COST", "40", "0"),
                line("EXPENSE.ADMINISTRATIVE", "10", "0"),
                line("INCOME.NON_OPERATING", "0", "5"),
                line("EXPENSE.NON_OPERATING", "2", "0"),
                line("EXPENSE.INCOME_TAX", "8", "0"));
        List<ReportingRepository.StatutoryAccountAmount> month = List.of(
                line("INCOME.MAIN_BUSINESS_REVENUE", "0", "100"),
                line("EXPENSE.MAIN_BUSINESS_COST", "30", "0"),
                line("EXPENSE.ADMINISTRATIVE", "5", "0"),
                line("INCOME.NON_OPERATING", "0", "2"),
                line("EXPENSE.NON_OPERATING", "1", "0"),
                line("EXPENSE.INCOME_TAX", "3", "0"));

        StatutoryReportResponses.Statement result = calculator.calculate("income-statement", "2026-06",
                "2011-17", formula("INCOME_STATEMENT"), ytd, month);

        assertThat(result.groups()).singleElement()
                .satisfies(group -> assertThat(group.lines()).hasSize(32));
        assertThat(amount(result, 21).primaryAmount()).isEqualByComparingTo("100");
        assertThat(amount(result, 21).comparativeAmount()).isEqualByComparingTo("65");
        assertThat(amount(result, 32).primaryAmount()).isEqualByComparingTo("95");
        assertThat(amount(result, 32).comparativeAmount()).isEqualByComparingTo("63");
    }

    @Test
    void renameCannotAffectCalculationAndMultipleLeavesSharingKeyAreSummed() throws Exception {
        List<ReportingRepository.StatutoryAccountAmount> source = List.of(
                line("EXPENSE.ADMINISTRATIVE", "4", "0"),
                line("EXPENSE.ADMINISTRATIVE", "6", "0"));

        StatutoryReportResponses.Statement result = calculator.calculate("income-statement", "2026-06",
                "2011-17", formula("INCOME_STATEMENT"), source, source);

        assertThat(amount(result, 11).primaryAmount()).isZero();
        assertThat(amount(result, 14).primaryAmount()).isEqualByComparingTo("10.00");
    }

    private StatutoryReportResponses.Line amount(StatutoryReportResponses.Statement result, int lineNo) {
        return result.groups().stream().flatMap(group -> group.lines().stream())
                .filter(line -> line.lineNo() == lineNo).findFirst().orElseThrow();
    }

    private JsonNode formula(String code) throws Exception {
        try (var input = getClass().getResourceAsStream("/accounting-standards/SME/2011-17.json")) {
            JsonNode standard = mapper.readTree(input);
            for (JsonNode formula : standard.path("formulas")) {
                if (code.equals(formula.path("code").asText())) return formula.path("definition");
            }
        }
        throw new IllegalArgumentException("formula not found: " + code);
    }

    private ReportingRepository.StatutoryAccountAmount line(String key, String debit, String credit) {
        BigDecimal d = new BigDecimal(debit);
        BigDecimal c = new BigDecimal(credit);
        return new ReportingRepository.StatutoryAccountAmount(
                UUID.randomUUID(), "renamed-account", key,
                BigDecimal.ZERO, BigDecimal.ZERO, d, c, d, c);
    }
}
