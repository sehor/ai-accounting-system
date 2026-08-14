package com.example.accounting.voucher;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class VoucherControllerTest {

    private final VoucherService voucherService = org.mockito.Mockito.mock(VoucherService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new VoucherController(new CurrentUserResolver(true), voucherService)).build();

    @Test
    void filtersVoucherPageByPeriodAndExposesTotalCount() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        VoucherRequests.Search search = new VoucherRequests.Search("2026-06", LocalDate.of(2026, 6, 1),
                LocalDate.of(2026, 6, 30), "工资");
        when(voucherService.list(userId, ledgerId, search, 20, 20)).thenReturn(List.of());
        when(voucherService.count(userId, ledgerId, search)).thenReturn(42L);

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/vouchers", ledgerId)
                        .header("X-User-Id", userId)
                        .queryParam("periodCode", "2026-06")
                        .queryParam("startDate", "2026-06-01")
                        .queryParam("endDate", "2026-06-30")
                        .queryParam("keyword", "工资")
                        .queryParam("limit", "20")
                        .queryParam("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Total-Count"));

        verify(voucherService).list(userId, ledgerId, search, 20, 20);
        verify(voucherService).count(userId, ledgerId, search);
    }

    @Test
    void acceptsBlankVoucherRowsForServiceSideFiltering() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();

        mockMvc.perform(post("/v1/ledgers/{ledgerId}/vouchers", ledgerId)
                        .header("X-User-Id", userId)
                        .header("Idempotency-Key", "create-voucher")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "voucherDate": "2026-06-15",
                                  "voucherType": "记",
                                  "lines": [
                                    {
                                      "side": "DEBIT",
                                      "currency": "CNY",
                                      "originalAmount": "",
                                      "exchangeRate": "1",
                                      "dimensions": []
                                    }
                                  ]
                                }
                                """))
                .andExpect(status().isCreated());

        verify(voucherService).create(eq(userId), eq(ledgerId), argThat(request ->
                request.lines().size() == 1
                        && request.lines().getFirst().accountId() == null
                        && request.lines().getFirst().originalAmount() == null), eq("create-voucher"));
    }
}
