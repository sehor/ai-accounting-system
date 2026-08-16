package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.identity.CurrentUserResolver;
import com.example.accounting.shared.web.ApiProblemException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AccountManagementIntegrationTest {

    @Autowired
    private LedgerService ledgers;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void clonesVersionedTemplateAndEnforcesTreeLocksVersionsDeletionAndAudit() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-management", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        List<LedgerResponses.Account> initial = ledgers.listAccounts(owner, ledgerId);
        assertThat(initial).hasSize(18).allMatch(LedgerResponses.Account::isTemplate);
        assertThat(ledgers.listCashFlowItems(owner, ledgerId)).hasSize(16);
        assertThat(ledgers.listDimensionTypes(owner, ledgerId)).hasSize(5);

        LedgerResponses.Account bank = initial.stream()
                .filter(account -> account.code().equals("1002")).findFirst().orElseThrow();
        LedgerResponses.Account child = ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        "100201", "基本户", "CURRENT_ASSET", "DEBIT", bank.id(),
                        false, null, false, null, List.of()));
        assertThat(child.parentId()).isEqualTo(bank.id());
        assertThat(child.level()).isEqualTo(2);
        assertThat(child.standardAccountKey()).isEqualTo("ASSET.BANK_DEPOSIT");
        assertThat(ledgers.findAccount(owner, ledgerId, bank.id()).isLeaf()).isFalse();

        assertProblem("ACCOUNT_CODE_RULE_LOCKED", () -> ledgers.updateAccountCodeRule(
                owner, ledgerId, new LedgerRequests.AccountCodeRuleUpdate(2, 2, 2)));
        assertProblem("ACCOUNT_TEMPLATE_LOCKED", () -> ledgers.updateAccount(
                owner, ledgerId, bank.id(), new LedgerRequests.AccountPatch(
                        bank.version(), "1003", null, null, null, null, null,
                        null, null, null, null, null)));

        LedgerResponses.Account renamed = ledgers.updateAccount(owner, ledgerId, child.id(),
                new LedgerRequests.AccountPatch(child.version(), null, "人民币基本户", null,
                        null, null, null, null, null, null, null, null));
        assertThat(renamed.standardAccountKey()).isEqualTo("ASSET.BANK_DEPOSIT");
        assertThat(renamed.name()).isEqualTo("人民币基本户");
        assertProblem("ACCOUNT_VERSION_CONFLICT", () -> ledgers.updateAccount(
                owner, ledgerId, child.id(), new LedgerRequests.AccountPatch(
                        child.version(), null, "陈旧写入", null, null, null,
                        null, null, null, null, null, null)));

        ledgers.deleteAccount(owner, ledgerId, child.id(), renamed.version());
        assertThat(ledgers.listAccounts(owner, ledgerId))
                .noneMatch(account -> account.id().equals(child.id()));
        assertThat(jdbc.queryForObject("""
                select count(*) from audit_revision
                where ledger_id = ? and aggregate_type = 'ACCOUNT'
                """, Integer.class, ledgerId)).isGreaterThanOrEqualTo(3);
    }

    @Test
    void generatesNextChildAccountCodeAndRetrievesCodeRule() {
        UUID owner = UUID.randomUUID();
        UUID ledgerId = ledgers.create(user(owner), new LedgerRequests.Create(
                "account-code-test", "SME", "2011-17", "CNY",
                LocalDate.of(2026, 1, 1), false)).id();

        AccountCodeRule rule = ledgers.getAccountCodeRule(owner, ledgerId);
        assertThat(rule).isNotNull();
        assertThat(rule.level2Width()).isEqualTo(2);
        assertThat(rule.level3Width()).isEqualTo(2);
        assertThat(rule.level4Width()).isEqualTo(2);

        LedgerResponses.Account bank = ledgers.listAccounts(owner, ledgerId).stream()
                .filter(account -> account.code().equals("1002")).findFirst().orElseThrow();

        // First child should be 100201
        String firstCode = ledgers.nextChildAccountCode(owner, ledgerId, bank.id());
        assertThat(firstCode).isEqualTo("100201");

        // Create 100201
        LedgerResponses.Account child1 = ledgers.createAccount(owner, ledgerId,
                new LedgerRequests.AccountCreate(
                        firstCode, "基本户", "CURRENT_ASSET", "DEBIT", bank.id(),
                        false, null, false, null, List.of()));

        // Next child under bank should be 100202
        String secondCode = ledgers.nextChildAccountCode(owner, ledgerId, bank.id());
        assertThat(secondCode).isEqualTo("100202");

        // Next child under child1 (level 2) should be 10020101
        String grandchildCode = ledgers.nextChildAccountCode(owner, ledgerId, child1.id());
        assertThat(grandchildCode).isEqualTo("10020101");
    }

    @Test
    void rejectsUnknownStandard() {
        UUID owner = UUID.randomUUID();
        assertProblem("ACCOUNTING_STANDARD_NOT_FOUND", () -> ledgers.create(user(owner),
                new LedgerRequests.Create("bad-standard", "SME", "missing", "CNY",
                        LocalDate.of(2026, 1, 1), false)));
    }

    private CurrentUserResolver.ResolvedUser user(UUID id) {
        return new CurrentUserResolver.ResolvedUser(id, "test", id.toString());
    }

    private void assertProblem(String code, Runnable action) {
        assertThatThrownBy(action::run)
                .isInstanceOfSatisfying(ApiProblemException.class,
                        exception -> assertThat(exception.code()).isEqualTo(code));
    }
}
