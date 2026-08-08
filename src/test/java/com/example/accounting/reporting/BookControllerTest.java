package com.example.accounting.reporting;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class BookControllerTest {

    private final ReportingService reportingService = org.mockito.Mockito.mock(ReportingService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new BookController(new CurrentUserResolver(true), reportingService)).build();

    @Test
    void exposesIndependentGeneralAndSubLedgerResources() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID accountId = UUID.randomUUID();
        ReportResponses.Pagination pagination = new ReportResponses.Pagination(1, 50, 0, 0);
        when(reportingService.generalLedgerBook(userId, ledgerId, "2026-06", 1, 50))
                .thenReturn(new ReportResponses.GeneralLedgerPage("2026-06", List.of(), pagination));
        when(reportingService.subLedgerBook(userId, ledgerId, "2026-06", accountId, 1, 50))
                .thenReturn(new ReportResponses.SubLedgerPage(
                        "2026-06", accountId, "1002", "银行存款", "DEBIT", BigDecimal.ZERO,
                        List.of(), BigDecimal.ZERO, BigDecimal.ZERO, "DEBIT", BigDecimal.ZERO, pagination));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/books/general-ledger", ledgerId)
                        .header("X-User-Id", userId).queryParam("periodCode", "2026-06"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periodCode").value("2026-06"))
                .andExpect(jsonPath("$.data").isArray());
        mockMvc.perform(get("/v1/ledgers/{ledgerId}/books/sub-ledger", ledgerId)
                        .header("X-User-Id", userId).queryParam("periodCode", "2026-06")
                        .queryParam("accountId", accountId.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value("1002"));

        verify(reportingService).generalLedgerBook(userId, ledgerId, "2026-06", 1, 50);
        verify(reportingService).subLedgerBook(userId, ledgerId, "2026-06", accountId, 1, 50);
    }
}
