package com.example.accounting.exchange;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.accounting.identity.CurrentUserResolver;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DataExchangeControllerTest {

    private final KingdeeExchange service = mock(KingdeeExchange.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
            new DataExchangeController(new CurrentUserResolver(true), service)).build();

    @Test
    void exposesKingdeeImportAndExportEndpoints() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(service.importKingdee(eq(actorId), eq(ledgerId), eq("upload-1"), eq(3L), any()))
                .thenReturn(new KingdeeExchange.ImportResult(1, 2));
        when(service.exportKingdee(actorId, ledgerId, false)).thenReturn(new byte[]{1, 2, 3});

        mockMvc.perform(multipart("/v1/ledgers/{ledgerId}/data-exchange/kingdee:import", ledgerId)
                        .file(new MockMultipartFile("file", "kingdee.xlsx",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                new byte[]{1, 2, 3}))
                        .header("X-User-Id", actorId)
                        .header("Idempotency-Key", "upload-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.voucherCount").value(1))
                .andExpect(jsonPath("$.rowCount").value(2));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/data-exchange/kingdee:export", ledgerId)
                        .header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"kingdee-vouchers.xlsx\""))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));

        verify(service).exportKingdee(actorId, ledgerId, false);
    }

    @Test
    void passesTheMergeChoiceToTheExporter() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        when(service.exportKingdee(actorId, ledgerId, true)).thenReturn(new byte[]{4, 5, 6});

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/data-exchange/kingdee:export", ledgerId)
                        .queryParam("mergeEntries", "true")
                        .header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(content().bytes(new byte[]{4, 5, 6}));

        verify(service).exportKingdee(actorId, ledgerId, true);
    }
}
