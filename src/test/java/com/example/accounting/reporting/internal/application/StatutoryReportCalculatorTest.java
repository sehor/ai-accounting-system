package com.example.accounting.reporting.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.reporting.ReportResponses;
import com.example.accounting.reporting.StatutoryReportResponses;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StatutoryReportCalculatorTest {

    private final StatutoryReportCalculator calculator = new StatutoryReportCalculator();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void calculatesAllProfitRowsAndBothActivityColumns() throws Exception {
        List<ReportResponses.TrialBalanceLine> ytd = List.of(
                line("5001", "主营业务收入", "REVENUE", "0", "150"),
                line("5401", "主营业务成本", "EXPENSE", "40", "0"),
                line("5602", "管理费用", "EXPENSE", "10", "0"),
                line("5301", "营业外收入", "REVENUE", "0", "5"),
                line("5711", "营业外支出", "EXPENSE", "2", "0"),
                line("5801", "所得税费用", "EXPENSE", "8", "0"));
        List<ReportResponses.TrialBalanceLine> month = List.of(
                line("5001", "主营业务收入", "REVENUE", "0", "100"),
                line("5401", "主营业务成本", "EXPENSE", "30", "0"),
                line("5602", "管理费用", "EXPENSE", "5", "0"),
                line("5301", "营业外收入", "REVENUE", "0", "2"),
                line("5711", "营业外支出", "EXPENSE", "1", "0"),
                line("5801", "所得税费用", "EXPENSE", "3", "0"));

        StatutoryReportResponses.Statement result = calculator.calculate("income-statement", "2026-06",
                "2011-17", mapper.readTree("{\"statutory\":{\"template\":\"SME-2011-17\"}}"), ytd, month);

        assertThat(result.groups()).singleElement()
                .satisfies(group -> assertThat(group.lines()).hasSize(32));
        StatutoryReportResponses.Line operating = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 21).findFirst().orElseThrow();
        assertThat(operating.primaryAmount()).isEqualByComparingTo("100");
        assertThat(operating.comparativeAmount()).isEqualByComparingTo("65");
        StatutoryReportResponses.Line net = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 32).findFirst().orElseThrow();
        assertThat(net.primaryAmount()).isEqualByComparingTo("95");
        assertThat(net.comparativeAmount()).isEqualByComparingTo("63");
    }

    @Test
    void reclassifiesAbnormalReceivableAndKeepsBalanceEquationCheck() throws Exception {
        List<ReportResponses.TrialBalanceLine> current = List.of(
                line("1001", "库存现金", "ASSET", "100", "0"),
                line("1122", "应收账款", "ASSET", "0", "20"),
                line("3001", "实收资本", "EQUITY", "0", "80"),
                line("3103", "本年利润", "EQUITY", "0", "0"));
        StatutoryReportResponses.Statement result = calculator.calculate("balance-sheet", "2026-06",
                "2011-17", mapper.readTree("{\"statutory\":{\"template\":\"SME-2011-17\"}}"), current, current);

        StatutoryReportResponses.Line receivable = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 4).findFirst().orElseThrow();
        assertThat(receivable.primaryAmount()).isZero();
        StatutoryReportResponses.Line prepayment = result.groups().get(0).lines().stream()
                .filter(line -> line.lineNo() == 5).findFirst().orElseThrow();
        assertThat(prepayment.primaryAmount()).isZero();
        assertThat(result.groups().get(1).lines().stream().filter(line -> line.lineNo() > 0)).hasSize(23);
        assertThat(result.checks()).allSatisfy(check -> assertThat(check.passed()).isTrue());
    }

    private ReportResponses.TrialBalanceLine line(String code, String name, String category,
                                                   String debit, String credit) {
        BigDecimal d = new BigDecimal(debit);
        BigDecimal c = new BigDecimal(credit);
        return new ReportResponses.TrialBalanceLine(UUID.randomUUID(), code, name, category,
                BigDecimal.ZERO, BigDecimal.ZERO, d, c, d, c, d, c, d.subtract(c));
    }
}
