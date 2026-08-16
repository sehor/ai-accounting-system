package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.audit.AuditSnapshotSerializer;
import com.example.accounting.shared.web.ApiProblemException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class OpeningBalanceAuditIntegrationTest {

    @Autowired private LedgerService ledgers;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private FailingAuditSnapshotSerializer auditSnapshots;

    @AfterEach
    void disarmAuditFailure() {
        auditSnapshots.disarm();
    }

    @Test
    void replaceImportAndConfirmAppendReconstructableAggregateRevisions() {
        Fixture fixture = fixture("opening-audit-chain");
        ledgers.replaceOpeningBalances(fixture.user(), fixture.ledger(), lines(fixture, "10.00"), "initial load");

        String csv = "periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate\n"
                + fixture.periodCode() + "," + fixture.debitCode() + ",CNY,,20.00,0,1\n"
                + fixture.periodCode() + "," + fixture.creditCode() + ",CNY,,0,20.00,1\n";
        ledgers.importOpeningBalances(fixture.user(), fixture.ledger(),
                new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "csv correction");
        ledgers.confirmOpeningBalances(fixture.user(), fixture.ledger(), "approved opening");

        List<AuditRow> revisions = jdbc.query("""
                select revision, action, actor_id, reason, before_data::text, after_data::text
                from audit_revision
                where ledger_id = ? and aggregate_type = 'OPENING_BALANCE' and aggregate_id = ?
                order by revision
                """, (rs, row) -> new AuditRow(rs.getInt("revision"), rs.getString("action"),
                rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                rs.getString("before_data"), rs.getString("after_data")),
                fixture.ledger(), fixture.ledger());

        assertThat(revisions).extracting(AuditRow::revision).containsExactly(1, 2, 3);
        assertThat(revisions).extracting(AuditRow::action).containsExactly("REPLACE", "IMPORT", "CONFIRM");
        assertThat(revisions).allSatisfy(row -> assertThat(row.actorId()).isEqualTo(fixture.user()));
        assertThat(revisions).extracting(AuditRow::reason)
                .containsExactly("initial load", "csv correction", "approved opening");
        assertThat(revisions.get(0).beforeData()).contains("\"operation\":\"REPLACE\"").contains("\"balances\":[]");
        assertThat(revisions.get(0).afterData()).contains("\"debitOriginal\":10.00").contains("\"confirmed\":false");
        assertThat(revisions.get(1).beforeData()).contains("\"debitOriginal\":10.00");
        assertThat(revisions.get(1).afterData()).contains("\"debitOriginal\":20.00");
        assertThat(revisions.get(2).beforeData()).contains("\"confirmed\":false");
        assertThat(revisions.get(2).afterData()).contains("\"confirmed\":true");
    }

    @Test
    void auditSerializationFailureRollsBackOpeningBalanceReplacement() {
        Fixture fixture = fixture("opening-audit-rollback");
        ledgers.replaceOpeningBalances(fixture.user(), fixture.ledger(), lines(fixture, "10.00"), "baseline");
        long revisionsBefore = revisionCount(fixture.ledger());
        auditSnapshots.arm();

        assertThatThrownBy(() -> ledgers.replaceOpeningBalances(
                fixture.user(), fixture.ledger(), lines(fixture, "99.00"), "must roll back"))
                .isInstanceOf(ApiProblemException.class)
                .extracting(error -> ((ApiProblemException) error).code())
                .isEqualTo("AUDIT_SNAPSHOT_FAILED");

        assertThat(ledgers.listOpeningBalances(fixture.user(), fixture.ledger()))
                .extracting(LedgerResponses.OpeningBalance::debitOriginal)
                .contains(new BigDecimal("10.00"));
        assertThat(revisionCount(fixture.ledger())).isEqualTo(revisionsBefore);
    }

    @Test
    void concurrentStructuredReplacementsKeepAContinuousCompleteAuditChain() throws Exception {
        StructuredFixture fixture = structuredFixture("opening-audit-concurrent");
        ledgers.replaceOpeningBalances(fixture.user(), fixture.ledger(),
                structuredLines(fixture, "10.00"), "structured baseline");

        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                await(barrier);
                return ledgers.replaceOpeningBalances(fixture.user(), fixture.ledger(),
                        structuredLines(fixture, "21.25"), "concurrent first");
            });
            Future<?> second = executor.submit(() -> {
                await(barrier);
                return ledgers.replaceOpeningBalances(fixture.user(), fixture.ledger(),
                        structuredLines(fixture, "31.75"), "concurrent second");
            });
            first.get();
            second.get();
        } finally {
            executor.shutdownNow();
        }
        ledgers.confirmOpeningBalances(fixture.user(), fixture.ledger(), "structured confirmation");

        List<AuditRow> revisions = auditRows(fixture.ledger());
        assertThat(revisions).extracting(AuditRow::revision).containsExactly(1, 2, 3, 4);
        assertThat(revisions).extracting(AuditRow::action)
                .containsExactly("REPLACE", "REPLACE", "REPLACE", "CONFIRM");
        assertThat(revisions).allSatisfy(row -> assertThat(row.actorId()).isEqualTo(fixture.user()));
        assertThat(revisions.subList(1, 3)).extracting(AuditRow::reason)
                .containsExactlyInAnyOrder("concurrent first", "concurrent second");

        ObjectMapper mapper = new ObjectMapper();
        for (AuditRow revision : revisions) {
            for (JsonNode snapshot : List.of(mapper.readTree(revision.beforeData()),
                    mapper.readTree(revision.afterData()))) {
                assertThat(snapshot.path("actorId").asText()).isEqualTo(fixture.user().toString());
                assertThat(snapshot.path("operation").asText()).isEqualTo(revision.action());
                assertThat(snapshot.path("reason").asText()).isEqualTo(revision.reason());
            }
        }
        for (int index = 1; index < revisions.size(); index++) {
            JsonNode previousAfter = mapper.readTree(revisions.get(index - 1).afterData());
            JsonNode currentBefore = mapper.readTree(revisions.get(index).beforeData());
            assertThat(currentBefore.path("balances")).isEqualTo(previousAfter.path("balances"));
            assertThat(currentBefore.path("confirmed")).isEqualTo(previousAfter.path("confirmed"));
        }

        AuditRow lastReplace = revisions.get(2);
        BigDecimal finalOriginal = "concurrent first".equals(lastReplace.reason())
                ? new BigDecimal("21.25") : new BigDecimal("31.75");
        JsonNode confirmedSnapshot = mapper.readTree(revisions.get(3).afterData());
        JsonNode debit = balance(confirmedSnapshot, fixture.debitAccount());
        JsonNode credit = balance(confirmedSnapshot, fixture.creditAccount());
        BigDecimal expectedBase = finalOriginal.multiply(fixture.exchangeRate())
                .setScale(2, java.math.RoundingMode.HALF_UP);

        assertThat(confirmedSnapshot.path("actorId").asText()).isEqualTo(fixture.user().toString());
        assertThat(confirmedSnapshot.path("operation").asText()).isEqualTo("CONFIRM");
        assertThat(confirmedSnapshot.path("reason").asText()).isEqualTo("structured confirmation");
        assertThat(confirmedSnapshot.path("confirmed").asBoolean()).isTrue();
        assertOpeningFields(debit, fixture, fixture.debitAccount(),
                finalOriginal, BigDecimal.ZERO, expectedBase, BigDecimal.ZERO);
        assertOpeningFields(credit, fixture, fixture.creditAccount(),
                BigDecimal.ZERO, finalOriginal, BigDecimal.ZERO, expectedBase);

        List<LedgerResponses.OpeningBalance> finalBalances =
                ledgers.listOpeningBalances(fixture.user(), fixture.ledger());
        assertThat(finalBalances).hasSize(2).allSatisfy(value -> {
            assertThat(value.currency()).isEqualTo("USD");
            assertThat(value.exchangeRate()).isEqualByComparingTo(fixture.exchangeRate());
            assertThat(value.confirmed()).isTrue();
            assertThat(value.dimensions()).singleElement().satisfies(dimension -> {
                assertThat(dimension.dimensionTypeId()).isEqualTo(fixture.dimensionType());
                assertThat(dimension.dimensionValueId()).isEqualTo(fixture.dimensionValue());
            });
        });
        assertThat(finalBalances).filteredOn(value -> value.accountId().equals(fixture.debitAccount()))
                .singleElement().satisfies(value -> {
                    assertThat(value.debitOriginal()).isEqualByComparingTo(finalOriginal);
                    assertThat(value.debitBase()).isEqualByComparingTo(expectedBase);
                });
        assertThat(finalBalances).filteredOn(value -> value.accountId().equals(fixture.creditAccount()))
                .singleElement().satisfies(value -> {
                    assertThat(value.creditOriginal()).isEqualByComparingTo(finalOriginal);
                    assertThat(value.creditBase()).isEqualByComparingTo(expectedBase);
                });
    }

    private Fixture fixture(String name) {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create(name, "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        Account debit = account(ledger, "DEBIT");
        Account credit = account(ledger, "CREDIT");
        return new Fixture(user, ledger, period, "2026-01", debit.id(), debit.code(), credit.id(), credit.code());
    }

    private StructuredFixture structuredFixture(String name) {
        UUID user = UUID.randomUUID();
        UUID ledger = ledgers.create(new CurrentUserResolver.ResolvedUser(user, "test", user.toString()),
                new LedgerRequests.Create(name, "SME", "v1", "CNY", LocalDate.of(2026, 1, 1), false)).id();
        UUID period = ledgers.periodId(ledger, "2026-01");
        LedgerResponses.DimensionType dimensionType = ledgers.listDimensionTypes(user, ledger).stream()
                .filter(value -> "CUSTOMER".equals(value.code())).findFirst().orElseThrow();
        UUID dimensionValue = ledgers.createDimensionValue(user, ledger, dimensionType.id(),
                new LedgerRequests.DimensionValueCreate("AUDIT-CUSTOMER", "Audit customer")).id();
        List<LedgerRequests.DimensionRequirement> requirements = List.of(
                new LedgerRequests.DimensionRequirement(dimensionType.id(), false));
        UUID debit = ledgers.createAccount(user, ledger, new LedgerRequests.AccountCreate(
                "9901", "Opening audit debit", "ASSET.CASH", "CURRENT_ASSET", "DEBIT", null,
                false, null, false, null, requirements)).id();
        UUID credit = ledgers.createAccount(user, ledger, new LedgerRequests.AccountCreate(
                "9902", "Opening audit credit", "LIABILITY.ACCOUNTS_PAYABLE",
                "CURRENT_LIABILITY", "CREDIT", null,
                false, null, false, null, requirements)).id();
        return new StructuredFixture(user, ledger, period, debit, credit,
                dimensionType.id(), dimensionValue, new BigDecimal("7.12345678"));
    }

    private Account account(UUID ledger, String normalBalance) {
        return jdbc.queryForObject("""
                select id, code from ledger_account a
                where ledger_id = ? and normal_balance = ? and status = 'ACTIVE'
                  and not exists (select 1 from ledger_account child
                                  where child.ledger_id = a.ledger_id and child.parent_id = a.id)
                order by code limit 1
                """, (rs, row) -> new Account(rs.getObject("id", UUID.class), rs.getString("code")),
                ledger, normalBalance);
    }

    private List<LedgerRequests.OpeningBalanceLine> lines(Fixture fixture, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return List.of(
                new LedgerRequests.OpeningBalanceLine(fixture.debitAccount(), fixture.period(), "CNY", "",
                        value, BigDecimal.ZERO, BigDecimal.ONE),
                new LedgerRequests.OpeningBalanceLine(fixture.creditAccount(), fixture.period(), "CNY", "",
                        BigDecimal.ZERO, value, BigDecimal.ONE));
    }

    private List<LedgerRequests.OpeningBalanceLine> structuredLines(
            StructuredFixture fixture, String amount) {
        BigDecimal value = new BigDecimal(amount);
        List<LedgerRequests.OpeningBalanceDimension> dimensions = List.of(
                new LedgerRequests.OpeningBalanceDimension(
                        fixture.dimensionType(), fixture.dimensionValue()));
        return List.of(
                new LedgerRequests.OpeningBalanceLine(fixture.debitAccount(), fixture.period(), "USD", "ignored",
                        value, BigDecimal.ZERO, fixture.exchangeRate(), dimensions),
                new LedgerRequests.OpeningBalanceLine(fixture.creditAccount(), fixture.period(), "USD", "ignored",
                        BigDecimal.ZERO, value, fixture.exchangeRate(), dimensions));
    }

    private List<AuditRow> auditRows(UUID ledger) {
        return jdbc.query("""
                select revision, action, actor_id, reason, before_data::text, after_data::text
                from audit_revision
                where ledger_id = ? and aggregate_type = 'OPENING_BALANCE' and aggregate_id = ?
                order by revision
                """, (rs, row) -> new AuditRow(rs.getInt("revision"), rs.getString("action"),
                rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                rs.getString("before_data"), rs.getString("after_data")), ledger, ledger);
    }

    private JsonNode balance(JsonNode snapshot, UUID accountId) {
        for (JsonNode value : snapshot.path("balances")) {
            if (accountId.toString().equals(value.path("accountId").asText())) {
                return value;
            }
        }
        throw new AssertionError("Opening balance snapshot is missing account " + accountId);
    }

    private void assertOpeningFields(JsonNode value, StructuredFixture fixture, UUID accountId,
                                     BigDecimal debitOriginal, BigDecimal creditOriginal,
                                     BigDecimal debitBase, BigDecimal creditBase) {
        assertThat(value.path("id").asText()).isNotBlank();
        assertThat(value.path("ledgerId").asText()).isEqualTo(fixture.ledger().toString());
        assertThat(value.path("periodId").asText()).isEqualTo(fixture.period().toString());
        assertThat(value.path("accountId").asText()).isEqualTo(accountId.toString());
        assertThat(value.path("currency").asText()).isEqualTo("USD");
        assertThat(value.path("dimensionKey").asText()).isNotBlank();
        assertThat(value.path("exchangeRate").decimalValue()).isEqualByComparingTo(fixture.exchangeRate());
        assertThat(value.path("debitOriginal").decimalValue()).isEqualByComparingTo(debitOriginal);
        assertThat(value.path("creditOriginal").decimalValue()).isEqualByComparingTo(creditOriginal);
        assertThat(value.path("debitBase").decimalValue()).isEqualByComparingTo(debitBase);
        assertThat(value.path("creditBase").decimalValue()).isEqualByComparingTo(creditBase);
        assertThat(value.path("confirmed").asBoolean()).isTrue();
        JsonNode dimension = value.path("dimensions").get(0);
        assertThat(dimension.path("dimensionTypeId").asText()).isEqualTo(fixture.dimensionType().toString());
        assertThat(dimension.path("dimensionValueId").asText()).isEqualTo(fixture.dimensionValue().toString());
        assertThat(dimension.path("dimensionTypeCode").asText()).isEqualTo("CUSTOMER");
        assertThat(dimension.path("dimensionValueCode").asText()).isEqualTo("AUDIT-CUSTOMER");
    }

    private void await(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private long revisionCount(UUID ledger) {
        return jdbc.queryForObject("""
                select count(*) from audit_revision
                where ledger_id = ? and aggregate_type = 'OPENING_BALANCE'
                """, Long.class, ledger);
    }

    @TestConfiguration
    static class AuditFailureConfiguration {
        @Bean
        @Primary
        FailingAuditSnapshotSerializer failingAuditSnapshotSerializer() {
            return new FailingAuditSnapshotSerializer();
        }
    }

    static class FailingAuditSnapshotSerializer extends AuditSnapshotSerializer {
        private final AtomicBoolean fail = new AtomicBoolean();

        void arm() {
            fail.set(true);
        }

        void disarm() {
            fail.set(false);
        }

        @Override
        public String serialize(Object value) {
            if (fail.compareAndSet(true, false)) {
                throw new ApiProblemException(500, "AUDIT_SNAPSHOT_FAILED", "Audit snapshot failed",
                        "Injected audit serialization failure", false);
            }
            return super.serialize(value);
        }
    }

    private record Account(UUID id, String code) { }
    private record Fixture(UUID user, UUID ledger, UUID period, String periodCode,
                           UUID debitAccount, String debitCode, UUID creditAccount, String creditCode) { }
    private record StructuredFixture(UUID user, UUID ledger, UUID period,
                                     UUID debitAccount, UUID creditAccount,
                                     UUID dimensionType, UUID dimensionValue,
                                     BigDecimal exchangeRate) { }
    private record AuditRow(int revision, String action, UUID actorId, String reason,
                            String beforeData, String afterData) { }
}
