package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ApiProblemException;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
@org.junit.jupiter.api.Disabled("Creates ledgers; disabled until tests use an isolated database")
class Stage2BaseDataTest {

    @Autowired
    private LedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void managesDimensionsAndOnlyAcceptsValuesFromTheSameLedger() {
        UUID userId = UUID.randomUUID();
        CurrentUserResolver.ResolvedUser actor = new CurrentUserResolver.ResolvedUser(
                userId, "test", userId.toString());
        UUID ledgerId = ledgerService.create(actor, createRequest("dimensions")).id();

        LedgerResponses.DimensionType type = ledgerService.listDimensionTypes(userId, ledgerId).stream()
                .filter(item -> item.code().equals("CUSTOMER")).findFirst().orElseThrow();
        LedgerResponses.DimensionValue value = ledgerService.createDimensionValue(userId, ledgerId, type.id(),
                new LedgerRequests.DimensionValueCreate("C001", "Acme"));

        assertThat(ledgerService.listDimensionTypes(userId, ledgerId)).extracting("code").contains("CUSTOMER");
        assertThat(ledgerService.listDimensionValues(userId, ledgerId, type.id())).extracting("id")
                .containsExactly(value.id());
        assertThatThrownBy(() -> ledgerService.listDimensionValues(userId, UUID.randomUUID(), type.id()))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("not available");
    }

    @Test
    void savesAndConfirmsBalancedOpeningBalances() {
        UUID userId = UUID.randomUUID();
        CurrentUserResolver.ResolvedUser actor = new CurrentUserResolver.ResolvedUser(
                userId, "test", userId.toString());
        UUID ledgerId = ledgerService.create(actor, createRequest("opening")).id();
        UUID cashId = ledgerService.accountId(ledgerId, "1001");
        UUID capitalId = ledgerService.accountId(ledgerId, "3001");
        UUID periodId = ledgerService.periodId(ledgerId, "2026-01");

        LedgerRequests.OpeningBalanceLine cash = new LedgerRequests.OpeningBalanceLine(
                cashId, periodId, "CNY", "", new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ONE);
        LedgerRequests.OpeningBalanceLine capital = new LedgerRequests.OpeningBalanceLine(
                capitalId, periodId, "CNY", "", BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ONE);

        assertThat(ledgerService.replaceOpeningBalances(userId, ledgerId, List.of(cash, capital))).hasSize(2);
        assertThat(ledgerService.confirmOpeningBalances(userId, ledgerId)).isEqualTo(2);
        assertThat(ledgerService.listOpeningBalances(userId, ledgerId)).allMatch(LedgerResponses.OpeningBalance::confirmed);
    }

    @Test
    void rejectsUnbalancedOpeningBalances() {
        UUID userId = UUID.randomUUID();
        CurrentUserResolver.ResolvedUser actor = new CurrentUserResolver.ResolvedUser(
                userId, "test", userId.toString());
        UUID ledgerId = ledgerService.create(actor, createRequest("unbalanced")).id();
        UUID cashId = ledgerService.accountId(ledgerId, "1001");
        UUID periodId = ledgerService.periodId(ledgerId, "2026-01");
        ledgerService.replaceOpeningBalances(userId, ledgerId, List.of(new LedgerRequests.OpeningBalanceLine(
                cashId, periodId, "CNY", "", new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ONE)));

        assertThatThrownBy(() -> ledgerService.confirmOpeningBalances(userId, ledgerId))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("must balance");
    }

    @Test
    void replacesOpeningBalancesInsteadOfMergingThem() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                createRequest("replace-opening")).id();
        UUID periodId = ledgerService.periodId(ledgerId, "2026-01");
        LedgerRequests.OpeningBalanceLine cash = new LedgerRequests.OpeningBalanceLine(
                ledgerService.accountId(ledgerId, "1001"), periodId, "CNY", "",
                new BigDecimal("100.00"), BigDecimal.ZERO, BigDecimal.ONE);
        LedgerRequests.OpeningBalanceLine capital = new LedgerRequests.OpeningBalanceLine(
                ledgerService.accountId(ledgerId, "3001"), periodId, "CNY", "",
                BigDecimal.ZERO, new BigDecimal("100.00"), BigDecimal.ONE);

        ledgerService.replaceOpeningBalances(userId, ledgerId, List.of(cash, capital));

        assertThat(ledgerService.replaceOpeningBalances(userId, ledgerId, List.of(cash)))
                .singleElement()
                .extracting(LedgerResponses.OpeningBalance::accountId)
                .isEqualTo(cash.accountId());
    }

    @Test
    void keepsAtLeastOneActiveOwner() {
        UUID firstOwner = UUID.randomUUID();
        UUID secondOwner = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(firstOwner, "test", firstOwner.toString()),
                createRequest("owner-guard")).id();
        ledgerService.create(new CurrentUserResolver.ResolvedUser(secondOwner, "test", secondOwner.toString()),
                createRequest("second-owner-user"));
        ledgerService.addMember(firstOwner, ledgerId, new LedgerRequests.AddMember(secondOwner, LedgerRole.OWNER));

        ledgerService.updateMember(firstOwner, ledgerId, firstOwner,
                new LedgerRequests.UpdateMember(LedgerRole.VIEWER, MembershipStatus.ACTIVE));

        assertThatThrownBy(() -> ledgerService.updateMember(secondOwner, ledgerId, secondOwner,
                new LedgerRequests.UpdateMember(LedgerRole.VIEWER, MembershipStatus.ACTIVE)))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("LAST_OWNER_REQUIRED");
    }

    @Test
    void closesAndReopensPeriodsWithAnAuditTrail() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                createRequest("period-audit")).id();
        UUID periodId = ledgerService.periodId(ledgerId, "2026-01");

        LedgerResponses.Period closed = ledgerService.closePeriod(userId, ledgerId, periodId,
                new LedgerRequests.PeriodAction("month end"));
        assertThat(closed.status()).isEqualTo("CLOSED");

        LedgerResponses.Period reopened = ledgerService.reopenPeriod(userId, ledgerId, periodId,
                new LedgerRequests.PeriodAction("correction"));
        assertThat(reopened.status()).isEqualTo("OPEN");
        assertThat(jdbcTemplate.queryForObject(
                "select count(*) from period_action_audit where ledger_id = ? and period_id = ?",
                Integer.class, ledgerId, periodId)).isEqualTo(2);
    }

    @Test
    void importsOpeningBalancesFromCsvAndReportsTheFailingRowAndField() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                createRequest("csv")).id();
        String csv = "periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate\n"
                + "2026-01,1001,CNY,,100,0,1\n"
                + "2026-01,3001,CNY,,0,100,1\n";

        assertThat(ledgerService.importOpeningBalances(userId, ledgerId,
                new ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)))).hasSize(2);

        String invalidCsv = "periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate\n"
                + "2026-01,9999,CNY,,100,0,1\n";
        assertThatThrownBy(() -> ledgerService.importOpeningBalances(userId, ledgerId,
                new ByteArrayInputStream(invalidCsv.getBytes(java.nio.charset.StandardCharsets.UTF_8))))
                .isInstanceOf(ApiProblemException.class)
                .hasMessageContaining("row 2 field accountCode");
    }

    @Test
    void importsNegativeOpeningBalancesWithoutChangingTheirSides() throws Exception {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                createRequest("negative-opening-csv")).id();
        String csv = "periodCode,accountCode,currency,dimensionKey,debitOriginal,creditOriginal,exchangeRate\n"
                + "2026-01,1001,CNY,,-25,0,1\n"
                + "2026-01,3001,CNY,,0,-25,1\n";

        List<LedgerResponses.OpeningBalance> imported = ledgerService.importOpeningBalances(
                userId, ledgerId,
                new ByteArrayInputStream(csv.getBytes(java.nio.charset.StandardCharsets.UTF_8)));

        assertThat(imported)
                .filteredOn(balance -> balance.accountId().equals(ledgerService.accountId(ledgerId, "1001")))
                .singleElement()
                .satisfies(balance -> {
                    assertThat(balance.debitOriginal()).isEqualByComparingTo("-25.0000");
                    assertThat(balance.creditOriginal()).isEqualByComparingTo("0.0000");
                });
        assertThat(imported)
                .filteredOn(balance -> balance.accountId().equals(ledgerService.accountId(ledgerId, "3001")))
                .singleElement()
                .satisfies(balance -> {
                    assertThat(balance.debitOriginal()).isEqualByComparingTo("0.0000");
                    assertThat(balance.creditOriginal()).isEqualByComparingTo("-25.0000");
                });
        assertThat(ledgerService.confirmOpeningBalances(userId, ledgerId)).isEqualTo(2);
    }

    @Test
    void rejectsOpeningBalancesWithBothSidesPopulatedWhenOneSideIsNegative() {
        UUID userId = UUID.randomUUID();
        UUID ledgerId = ledgerService.create(
                new CurrentUserResolver.ResolvedUser(userId, "test", userId.toString()),
                createRequest("both-opening-sides")).id();
        UUID periodId = ledgerService.periodId(ledgerId, "2026-01");

        assertThatThrownBy(() -> ledgerService.replaceOpeningBalances(userId, ledgerId, List.of(
                new LedgerRequests.OpeningBalanceLine(
                        ledgerService.accountId(ledgerId, "1001"), periodId, "CNY", "",
                        new BigDecimal("-25.00"), new BigDecimal("25.00"), BigDecimal.ONE))))
                .isInstanceOf(ApiProblemException.class)
                .extracting(exception -> ((ApiProblemException) exception).code())
                .isEqualTo("INVALID_OPENING_BALANCE");
    }

    private LedgerRequests.Create createRequest(String suffix) {
        return new LedgerRequests.Create("Stage2 " + suffix, "SME", "v1", "CNY",
                LocalDate.of(2026, 1, 15), false);
    }
}
