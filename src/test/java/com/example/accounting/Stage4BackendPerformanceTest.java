package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import com.example.accounting.audit.AuditResponses;
import com.example.accounting.audit.internal.application.DefaultAuditService;
import com.example.accounting.audit.internal.port.AuditRepository;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.reporting.internal.persistence.BalanceSnapshotRebuilder;
import com.example.accounting.reporting.internal.persistence.JdbcBalanceProjectionRepository;
import com.example.accounting.reporting.internal.port.BalanceProjectionRepository;
import com.example.accounting.voucher.internal.persistence.JdbcVoucherRepository;
import com.example.accounting.voucher.internal.port.VoucherRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;

class Stage4BackendPerformanceTest {

    @Test
    void auditCursorIsOpaqueBoundedAndRejectsMalformedValues() {
        UUID actor = UUID.randomUUID();
        UUID ledger = UUID.randomUUID();
        LedgerAccessService access = mock(LedgerAccessService.class);
        AuditRepository repository = mock(AuditRepository.class);
        when(access.requireMembership(actor, ledger)).thenReturn(LedgerRole.VIEWER);
        OffsetDateTime created = OffsetDateTime.parse("2026-01-01T00:00:00Z");
        AuditResponses.Entry entry = new AuditResponses.Entry(UUID.randomUUID(), "VOUCHER", UUID.randomUUID(),
                1, "CREATE", actor, null, created);
        when(repository.page(eq(ledger), anyInt(), any(), any(), any(), any())).thenReturn(List.of(entry));
        DefaultAuditService service = new DefaultAuditService(access, repository);

        AuditResponses.Page page = service.page(actor, ledger, 50, null, "VOUCHER", entry.aggregateId());

        assertThat(page.items()).containsExactly(entry);
        assertThat(page.nextCursor()).isNull();
        assertThatThrownBy(() -> service.page(actor, ledger, 201, null, null, null))
                .isInstanceOfSatisfying(com.example.accounting.shared.web.ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(422));
        assertThatThrownBy(() -> service.page(actor, ledger, 50, "not-a-cursor", null, null))
                .isInstanceOfSatisfying(com.example.accounting.shared.web.ApiProblemException.class,
                        problem -> assertThat(problem.status()).isEqualTo(422));
    }

    @Test
    void cleanupClampsEveryTransactionToOneThousandEvents() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        BalanceSnapshotRebuilder snapshots = mock(BalanceSnapshotRebuilder.class);
        JdbcBalanceProjectionRepository repository = new JdbcBalanceProjectionRepository(jdbc, snapshots);
        when(jdbc.update(anyString(), any(), anyInt())).thenReturn(1000);

        int removed = repository.cleanupAppliedEvents(OffsetDateTime.now(), 5000);

        assertThat(removed).isEqualTo(1000);
        verify(jdbc).update(anyString(), any(), eq(1000));
    }

    @Test
    void voucherLinesAndDimensionsUseJdbcBatchBoundaries() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        JdbcVoucherRepository repository = new JdbcVoucherRepository(jdbc);
        UUID ledger = UUID.randomUUID();
        UUID voucher = UUID.randomUUID();
        VoucherRepository.LineInsert line = new VoucherRepository.LineInsert(UUID.randomUUID(), ledger, voucher, 1,
                UUID.randomUUID(), "DEBIT", "CNY", java.math.BigDecimal.TEN, java.math.BigDecimal.ONE,
                java.math.BigDecimal.TEN, null, null, null, null, null);

        repository.createLines(List.of(line));
        repository.createLineDimensionsBatch(List.of(new VoucherRepository.LineDimensionInsert(
                line.lineId(), ledger, UUID.randomUUID(), UUID.randomUUID())));

        verify(jdbc, times(2)).batchUpdate(anyString(), any(BatchPreparedStatementSetter.class));
    }

}
