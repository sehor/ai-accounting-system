package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AccountCodeRuleTest {

    @Test
    void validatesContinuousCodesAndInfersParentsWithLedgerWidths() {
        AccountCodeRule rule = new AccountCodeRule(4, 3, 2);

        assertThat(rule.levelOf("1002")).isEqualTo(1);
        assertThat(rule.levelOf("10020001")).isEqualTo(2);
        assertThat(rule.levelOf("10020001003")).isEqualTo(3);
        assertThat(rule.levelOf("1002000100302")).isEqualTo(4);
        assertThat(rule.parentCode("10020001003")).contains("10020001");
        assertThat(rule.parentCode("1002")).isEmpty();
        assertThat(rule.levelOf("1002.01")).isZero();
        assertThat(rule.levelOf("1002-0001")).isZero();
        assertThat(rule.levelOf("1002001")).isZero();
    }

    @Test
    void rejectsUnsafeRules() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountCodeRule(0, 2, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountCodeRule(8, 8, 13));
    }

    @Test
    void generatesNextChildCodeCorrectly() {
        AccountCodeRule defaultRule = AccountCodeRule.DEFAULT; // 2, 2, 2
        // Level 1 parent, no children -> 100201
        assertThat(defaultRule.nextChildCode("1002", java.util.List.of())).isEqualTo("100201");
        // Level 1 parent, sequential children -> 100203
        assertThat(defaultRule.nextChildCode("1002", java.util.List.of("100201", "100202"))).isEqualTo("100203");
        // Level 1 parent with gap -> 100202
        assertThat(defaultRule.nextChildCode("1002", java.util.List.of("100201", "100205"))).isEqualTo("100202");
        // Level 2 parent -> 10020101
        assertThat(defaultRule.nextChildCode("100201", java.util.List.of())).isEqualTo("10020101");
        // Ignores grandchild or other parent accounts
        assertThat(defaultRule.nextChildCode("1002", java.util.List.of("10020101", "100301"))).isEqualTo("100201");

        // Custom rule 4, 3, 2
        AccountCodeRule customRule = new AccountCodeRule(4, 3, 2);
        assertThat(customRule.nextChildCode("1002", java.util.List.of())).isEqualTo("10020001");
        assertThat(customRule.nextChildCode("10020003", java.util.List.of())).isEqualTo("10020003001");
        assertThat(customRule.nextChildCode("10020003001", java.util.List.of())).isEqualTo("1002000300101");
    }

    @Test
    void rejectsInvalidParentForChildCode() {
        AccountCodeRule rule = AccountCodeRule.DEFAULT;
        // Level 4 parent cannot have children
        assertThatIllegalArgumentException().isThrownBy(() -> rule.nextChildCode("1002010101", java.util.List.of()));
        // Invalid parent code
        assertThatIllegalArgumentException().isThrownBy(() -> rule.nextChildCode("invalid", java.util.List.of()));
    }
}
