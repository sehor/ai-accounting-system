package com.example.accounting.periodclosing;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PeriodClosingControllerTest {
    private final PeriodClosingService service = org.mockito.Mockito.mock(PeriodClosingService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new PeriodClosingController(new CurrentUserResolver(true), service)).build();

    @Test
    void exposesStatusAndGenerationEndpoints() throws Exception {
        UUID user = UUID.randomUUID(); UUID ledger = UUID.randomUUID(); UUID period = UUID.randomUUID();
        PeriodClosingResponses.TrialBalanceTotals totals = new PeriodClosingResponses.TrialBalanceTotals(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, true);
        when(service.status(user, ledger, period)).thenReturn(new PeriodClosingResponses.Status(
                ledger, period, "2026-01", List.of(), List.of(), totals, true));
        when(service.generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER)).thenReturn(
                new PeriodClosingResponses.Step(PeriodClosingStepType.EXPENSE_TRANSFER,
                        PeriodClosingStepStatus.GENERATED, BigDecimal.TEN, UUID.randomUUID(), "fp", List.of(), null));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/period-closings/{periodId}", ledger, period)
                        .header("X-User-Id", user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.canClose").value(true));
        mockMvc.perform(post("/v1/ledgers/{ledgerId}/period-closings/{periodId}/steps/{step}:generate",
                        ledger, period, "EXPENSE_TRANSFER").header("X-User-Id", user))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GENERATED"));
        verify(service).status(user, ledger, period);
        verify(service).generate(user, ledger, period, PeriodClosingStepType.EXPENSE_TRANSFER);
    }
}
