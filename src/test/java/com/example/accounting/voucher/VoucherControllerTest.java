package com.example.accounting.voucher;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
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
        when(voucherService.list(userId, ledgerId, "2026-06", 20, 20)).thenReturn(List.of());
        when(voucherService.count(userId, ledgerId, "2026-06")).thenReturn(42L);

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/vouchers", ledgerId)
                        .header("X-User-Id", userId)
                        .queryParam("periodCode", "2026-06")
                        .queryParam("limit", "20")
                        .queryParam("offset", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(header().string("X-Total-Count", "42"))
                .andExpect(header().string("Access-Control-Expose-Headers", "X-Total-Count"));

        verify(voucherService).list(userId, ledgerId, "2026-06", 20, 20);
        verify(voucherService).count(userId, ledgerId, "2026-06");
    }
}
