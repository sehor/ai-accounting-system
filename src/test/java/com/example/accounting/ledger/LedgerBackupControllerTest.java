package com.example.accounting.ledger;

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
import com.example.accounting.shared.web.ProblemDetailExceptionHandler;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LedgerBackupControllerTest {

    private final LedgerBackupService backups = mock(LedgerBackupService.class);
    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(
                    new LedgerBackupController(new CurrentUserResolver(true), backups))
            .setControllerAdvice(new ProblemDetailExceptionHandler())
            .build();

    @Test
    void downloadsAndRestoresLedgerBackupArchives() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID restoredId = UUID.randomUUID();
        byte[] archive = new byte[]{'P', 'K', 3, 4};
        when(backups.backup(actorId, ledgerId)).thenReturn(archive);
        when(backups.restore(any(), eq("恢复账套"), eq((long) archive.length), any())).thenReturn(
                new LedgerResponses.Ledger(restoredId, "恢复账套", "SME", "2011-17", "CNY",
                        LocalDate.of(2026, 1, 1), false, "ACTIVE"));

        mockMvc.perform(get("/v1/ledgers/{ledgerId}/backup", ledgerId)
                        .header("X-User-Id", actorId))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"ledger-" + ledgerId + ".aibackup\""))
                .andExpect(content().bytes(archive));

        mockMvc.perform(multipart("/v1/ledger-restores")
                        .file(new MockMultipartFile("file", "backup.aibackup",
                                "application/vnd.ai-accounting.ledger-backup+zip", archive))
                        .param("name", "恢复账套")
                        .header("X-User-Id", actorId))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(restoredId.toString()))
                .andExpect(jsonPath("$.name").value("恢复账套"));

        verify(backups).backup(actorId, ledgerId);
        verify(backups).restore(any(), eq("恢复账套"), eq((long) archive.length), any());
    }
}
