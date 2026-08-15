package com.example.accounting.fixedasset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.example.accounting.fixedasset.internal.application.DefaultFixedAssetService;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.AssetRecord;
import com.example.accounting.fixedasset.internal.port.FixedAssetRepository.DepreciationHistory;
import com.example.accounting.ledger.LedgerAccessService;
import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerRole;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.shared.audit.AuditSnapshotSerializer;
import com.example.accounting.voucher.GeneratedVoucherCommandService;
import com.example.accounting.voucher.VoucherService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DefaultFixedAssetServiceAuditSnapshotTest {

    @Test
    void rejectsTheUpdateBeforeAnyWriteWhenBeforeSerializationFails() {
        assertSerializationFailureRollsBack(1);
    }

    @Test
    void rejectsTheUpdateBeforeAnyWriteWhenAfterSerializationFails() {
        assertSerializationFailureRollsBack(2);
    }

    @Test
    void recordsSuccessfulBeforeAndAfterSnapshotsWithPersistedVersion() throws Exception {
        Fixture fixture = fixture(new AuditSnapshotSerializer());
        AssetRecord persisted = asset(fixture.assetId(), fixture.ledgerId(), new BigDecimal("120.00"), 1L);
        when(fixture.assets().updateAsset(eq(fixture.ledgerId()), eq(fixture.assetId()), any(), eq(0L),
                eq(fixture.actorId()))).thenReturn(true);
        when(fixture.assets().findAsset(fixture.ledgerId(), fixture.assetId()))
                .thenReturn(Optional.of(fixture.current()), Optional.of(persisted));
        when(fixture.ledgers().listPeriods(fixture.actorId(), fixture.ledgerId())).thenReturn(List.of(
                new LedgerResponses.Period(fixture.changePeriodId(), fixture.ledgerId(), "2026-01",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN")));
        when(fixture.assets().depreciationBefore(eq(fixture.ledgerId()), eq(fixture.assetId()), any()))
                .thenReturn(new DepreciationHistory(BigDecimal.ZERO, 0));
        when(fixture.assets().periodDepreciation(fixture.ledgerId(), fixture.assetId(),
                fixture.changePeriodId())).thenReturn(BigDecimal.ZERO);
        ArgumentCaptor<String> beforeData = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> afterData = ArgumentCaptor.forClass(String.class);

        fixture.service().updateAsset(fixture.actorId(), fixture.ledgerId(), fixture.assetId(), fixture.request());

        verify(fixture.assets()).insertChange(eq(fixture.ledgerId()), eq(fixture.assetId()),
                eq(fixture.changePeriodId()), eq("correction"), eq(fixture.actorId()),
                beforeData.capture(), afterData.capture());
        ObjectMapper mapper = new ObjectMapper();
        JsonNode before = mapper.readTree(beforeData.getValue());
        JsonNode after = mapper.readTree(afterData.getValue());
        assertThat(before.path("version").asLong()).isZero();
        assertThat(before.path("originalCost").decimalValue()).isEqualByComparingTo("100.00");
        assertThat(after.path("version").asLong()).isEqualTo(1L);
        assertThat(after.path("originalCost").decimalValue()).isEqualByComparingTo("120.00");
        assertThat(after.path("id").asText()).isEqualTo(fixture.assetId().toString());
        assertThat(after.path("ledgerId").asText()).isEqualTo(fixture.ledgerId().toString());
    }

    @Test
    void rejectsPastAndFutureOpenChangePeriodsBeforeWriting() {
        Fixture fixture = fixture(new AuditSnapshotSerializer());
        UUID currentPeriodId = UUID.randomUUID();
        UUID pastPeriodId = UUID.randomUUID();
        UUID futurePeriodId = UUID.randomUUID();
        when(fixture.ledgers().listPeriods(fixture.actorId(), fixture.ledgerId())).thenReturn(List.of(
                period(currentPeriodId, fixture.ledgerId(), "2026-02", "2026-02-01", "2026-02-28", "OPEN"),
                period(pastPeriodId, fixture.ledgerId(), "2026-01", "2026-01-01", "2026-01-31", "OPEN"),
                period(futurePeriodId, fixture.ledgerId(), "2026-03", "2026-03-01", "2026-03-31", "OPEN")));

        assertChangePeriodRejected(fixture, pastPeriodId, 422, "FIXED_ASSET_CHANGE_PERIOD_PAST");
        assertChangePeriodRejected(fixture, futurePeriodId, 422, "FIXED_ASSET_CHANGE_PERIOD_FUTURE");
        verify(fixture.assets(), never()).updateAsset(any(), any(), any(), anyLong(), any());
    }

    @Test
    void rejectsClosedChangePeriodBeforeWriting() {
        Fixture fixture = fixture(new AuditSnapshotSerializer());
        UUID closedPeriodId = UUID.randomUUID();
        when(fixture.ledgers().listPeriods(fixture.actorId(), fixture.ledgerId())).thenReturn(List.of(
                period(closedPeriodId, fixture.ledgerId(), "2026-01", "2026-01-01", "2026-01-31", "CLOSED")));

        assertChangePeriodRejected(fixture, closedPeriodId, 409, "FIXED_ASSET_CHANGE_PERIOD_CLOSED");
        verify(fixture.assets(), never()).updateAsset(any(), any(), any(), anyLong(), any());
    }

    private void assertSerializationFailureRollsBack(int failingCall) {
        AuditSnapshotSerializer serializer = mock(AuditSnapshotSerializer.class);
        AtomicInteger calls = new AtomicInteger();
        when(serializer.serialize(any(), any(), any(), any())).thenAnswer(invocation -> {
            if (calls.incrementAndGet() == failingCall) {
                throw snapshotFailure();
            }
            return "{}";
        });
        Fixture fixture = fixture(serializer);

        assertThatThrownBy(() -> fixture.service().updateAsset(
                fixture.actorId(), fixture.ledgerId(), fixture.assetId(), fixture.request()))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(exception -> {
                    ApiProblemException problem = (ApiProblemException) exception;
                    assertThat(problem.status()).isEqualTo(500);
                    assertThat(problem.code()).isEqualTo("FIXED_ASSET_AUDIT_SNAPSHOT_FAILED");
                });

        verify(fixture.assets(), never()).updateAsset(any(), any(), any(), anyLong(), any());
        verify(fixture.assets(), never()).insertChange(any(), any(), any(), any(), any(), any(), any());
    }

    private void assertChangePeriodRejected(Fixture fixture, UUID periodId, int status, String code) {
        FixedAssetRequests.AssetPatch request = new FixedAssetRequests.AssetPatch(
                0L, null, null, null, new BigDecimal("120.00"), null, null, null, null,
                null, null, null, null, null, null, null, null, null, periodId, "correction", null);
        assertThatThrownBy(() -> fixture.service().updateAsset(
                fixture.actorId(), fixture.ledgerId(), fixture.assetId(), request))
                .isInstanceOf(ApiProblemException.class)
                .satisfies(exception -> {
                    ApiProblemException problem = (ApiProblemException) exception;
                    assertThat(problem.status()).isEqualTo(status);
                    assertThat(problem.code()).isEqualTo(code);
                });
    }

    private LedgerResponses.Period period(UUID id, UUID ledgerId, String code, String start, String end,
                                          String status) {
        return new LedgerResponses.Period(id, ledgerId, code, LocalDate.parse(start), LocalDate.parse(end), status);
    }

    private Fixture fixture(AuditSnapshotSerializer serializer) {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID assetId = UUID.randomUUID();
        UUID changePeriodId = UUID.randomUUID();
        FixedAssetRepository assets = mock(FixedAssetRepository.class);
        LedgerAccessService ledgerAccess = mock(LedgerAccessService.class);
        LedgerService ledgers = mock(LedgerService.class);
        VoucherService vouchers = mock(VoucherService.class);
        GeneratedVoucherCommandService generatedVouchers = mock(GeneratedVoucherCommandService.class);
        AssetRecord current = asset(assetId, ledgerId, new BigDecimal("100.00"), 0L);
        when(ledgerAccess.requireMembership(actorId, ledgerId)).thenReturn(LedgerRole.OWNER);
        when(assets.findAsset(ledgerId, assetId)).thenReturn(Optional.of(current));
        when(assets.hasAssetUsage(ledgerId, assetId)).thenReturn(false);
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of());
        when(ledgers.listPeriods(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Period(changePeriodId, ledgerId, "2026-01",
                        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31), "OPEN")));
        DefaultFixedAssetService service = new DefaultFixedAssetService(
                assets, ledgerAccess, ledgers, vouchers, generatedVouchers, serializer);
        FixedAssetRequests.AssetPatch request = new FixedAssetRequests.AssetPatch(
                0L, null, null, null, new BigDecimal("120.00"), null, null, null, null,
                null, null, null, null, null, null, null, null, null, changePeriodId, "correction", null);
        return new Fixture(actorId, ledgerId, assetId, changePeriodId, assets, ledgers, current, request, service);
    }

    private AssetRecord asset(UUID assetId, UUID ledgerId, BigDecimal originalCost, long version) {
        return new AssetRecord(assetId, ledgerId, UUID.randomUUID(), "CAT", "Category", "FA-1", "Asset",
                "ACTIVE", BigDecimal.ONE, LocalDate.of(2026, 1, 1), originalCost,
                BigDecimal.ZERO, 60, new BigDecimal("5.0000"), BigDecimal.ZERO, 0, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null, null, version);
    }

    private ApiProblemException snapshotFailure() {
        return new ApiProblemException(500, "FIXED_ASSET_AUDIT_SNAPSHOT_FAILED",
                "Fixed-asset audit snapshot failed", "The fixed-asset change could not be serialized", false);
    }

    private record Fixture(UUID actorId, UUID ledgerId, UUID assetId, UUID changePeriodId,
                           FixedAssetRepository assets, LedgerService ledgers, AssetRecord current,
                           FixedAssetRequests.AssetPatch request, DefaultFixedAssetService service) {
    }
}
