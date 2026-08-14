package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.accounting.identity.CurrentUserResolver;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class AccountSearchIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Test
    void searchesByExactAndFuzzyTextWithImmediateHierarchy() {
        UUID ownerId = UUID.randomUUID();
        UUID ledgerId = ledgers.create(
                new CurrentUserResolver.ResolvedUser(ownerId, "test", ownerId.toString()),
                new LedgerRequests.Create(
                        "account-search", "SME", "2011-17", "CNY",
                        LocalDate.of(2026, 1, 1), false)).id();
        LedgerResponses.Account child = ledgers.createAccount(ownerId, ledgerId,
                new LedgerRequests.AccountCreate(
                        "100201", "建设银行存款", "CURRENT_ASSET", "DEBIT"));

        var exactParent = ledgers.searchAccounts(
                ownerId, ledgerId, "1002", LedgerRequests.AccountMatchMode.EXACT, 10);
        var exactChild = ledgers.searchAccounts(
                ownerId, ledgerId, "建设银行存款", LedgerRequests.AccountMatchMode.EXACT, 10);
        var fuzzy = ledgers.searchAccounts(
                ownerId, ledgerId, "银行", LedgerRequests.AccountMatchMode.FUZZY, 10);

        assertThat(exactParent).singleElement().satisfies(match -> {
            assertThat(match.account().code()).isEqualTo("1002");
            assertThat(match.parent()).isNull();
            assertThat(match.children()).extracting(LedgerResponses.AccountSummary::code)
                    .contains("100201");
        });
        assertThat(exactChild).singleElement().satisfies(match -> {
            assertThat(match.account().id()).isEqualTo(child.id());
            assertThat(match.parent()).isNotNull();
            assertThat(match.parent().code()).isEqualTo("1002");
            assertThat(match.children()).isEmpty();
        });
        assertThat(fuzzy).extracting(match -> match.account().code())
                .startsWith("1002")
                .contains("100201");
    }
}
