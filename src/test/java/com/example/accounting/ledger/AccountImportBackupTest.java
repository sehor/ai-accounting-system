package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.example.accounting.ledger.internal.persistence.AccountImportRepository;
import com.example.accounting.ledger.internal.persistence.AccountManagementRepository;
import com.example.accounting.ledger.internal.port.LedgerBackupRepository;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountImportBackupTest {
    @TempDir Path storage;
    private final UUID actor = UUID.randomUUID();
    private final UUID ledger = UUID.randomUUID();
    private final UUID importId = UUID.randomUUID();
    private final LedgerService ledgers = mock(LedgerService.class);
    private final LedgerAccessService access = mock(LedgerAccessService.class);
    private final AccountManagementRepository accounts = mock(AccountManagementRepository.class);
    private final AccountImportRepository imports = mock(AccountImportRepository.class);
    private final LedgerBackupService backups = mock(LedgerBackupService.class);
    private AccountExchangeService service;

    @BeforeEach
    void setUp() {
        when(access.requireMembership(actor, ledger)).thenReturn(LedgerRole.EDITOR);
        when(imports.findHeader(ledger, importId)).thenReturn(header("OVERWRITE", "PREVIEW"));
        when(accounts.ledgerVersion(ledger)).thenReturn(1L);
        when(imports.commitRows(importId)).thenReturn(List.of(new AccountImportRepository.CommitRow(
                1, "{\"code\":\"1002\",\"name\":\"Bank\",\"category\":\"CURRENT_ASSET\",\"normalBalance\":\"DEBIT\",\"status\":\"ACTIVE\"}",
                null, null, "CREATE", false, "[]")));
        when(accounts.reconstructAccounts(eq(ledger), any(), anyList(), anyList(), eq(importId)))
                .thenReturn(new AccountManagementRepository.ReconstructResult(18, 1));
        service = spy(new AccountExchangeService(ledgers, access, accounts, imports, null, null, backups));
        doReturn(null).when(service).get(actor, ledger, importId);
    }

    @Test
    void backsUpBeforeReconstruction() {
        service.commit(actor, ledger, importId);
        var order = inOrder(accounts, backups, imports);
        order.verify(accounts).lockLedger(ledger);
        order.verify(backups).backupBeforeAccountOverwrite(actor, ledger, importId);
        order.verify(accounts).reconstructAccounts(eq(ledger), any(), argThat(rows -> rows.size() == 1), anyList(), eq(importId));
        order.verify(imports).markCommitted(importId, ledger, 1, 18);
    }

    @Test
    void stopsBeforeAnyImportWritesWhenBackupFails() {
        var failure = new ApiProblemException(500, "LEDGER_BACKUP_FAILED", "Backup failed", "Disk full", false);
        when(backups.backupBeforeAccountOverwrite(actor, ledger, importId)).thenThrow(failure);
        assertThatThrownBy(() -> service.commit(actor, ledger, importId)).isSameAs(failure);
        verify(accounts, never()).reconstructAccounts(any(), any(), anyList(), anyList(), any());
        verify(imports, never()).markCommitted(any(), any(), anyInt(), anyInt());
        verify(ledgers, never()).createDimensionType(any(), any(), any());
    }

    @Test
    void appendDoesNotCreateAnAutomaticBackup() {
        when(imports.findHeader(ledger, importId)).thenReturn(header("APPEND", "PREVIEW"));
        service.commit(actor, ledger, importId);
        verifyNoInteractions(backups);
        verify(accounts).appendAccounts(eq(ledger), any(), anyList(), anyList());
    }

    @Test
    void retryOfCommittedImportDoesNotCreateAnotherBackup() {
        when(imports.findHeader(ledger, importId)).thenReturn(header("OVERWRITE", "COMMITTED"));
        service.commit(actor, ledger, importId);
        verifyNoInteractions(backups);
        verify(accounts, never()).reconstructAccounts(any(), any(), anyList(), anyList(), any());
    }

    @Test
    void savesAnArchiveWithExistingBackupFormat() throws Exception {
        var backupService = actualBackups(storage);
        Path file = backupService.backupBeforeAccountOverwrite(actor, ledger, importId);
        assertThat(file).isEqualTo(storage.resolve("ledger-backups").resolve(ledger.toString())
                .resolve("before-account-import-" + importId + ".aibackup"));
        try (var zip = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(file)))) {
            assertThat(zip.getNextEntry().getName()).isEqualTo("manifest.json");
            assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains("AI-ACCOUNTING-LEDGER-BACKUP");
            assertThat(zip.getNextEntry().getName()).isEqualTo("data.json");
            assertThat(new String(zip.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains(ledger.toString());
        }
    }

    @Test
    void reportsBackupFailureWhenArchiveCannotBeSaved() throws Exception {
        Path blocked = Files.createFile(storage.resolve("not-a-directory"));
        assertThatThrownBy(() -> actualBackups(blocked).backupBeforeAccountOverwrite(actor, ledger, importId))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        ex -> assertThat(ex.code()).isEqualTo("LEDGER_BACKUP_FAILED"));
    }

    private LedgerBackupService actualBackups(Path root) {
        var repository = mock(LedgerBackupRepository.class);
        when(repository.ledgerJson(ledger)).thenReturn("{\"id\":\"" + ledger + "\",\"name\":\"Review\"}");
        when(repository.rowsJson(anyString(), eq(ledger))).thenReturn("[]");
        return new LedgerBackupService(access, null, repository, root.toString());
    }

    private AccountImportRepository.Header header(String mode, String status) {
        return new AccountImportRepository.Header(importId, ledger, "KINGDEE", mode, status, 1L,
                "review.xls", "hash", 1, 0, 1, 18, 2, 2, 2, "{}", "[]", "NOT_CONFIGURED");
    }
}
