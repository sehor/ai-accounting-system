package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class AccountCodeRuleTest {

    @Test
    void validatesLevelsAndInfersParentsWithConfiguredSeparator() {
        AccountCodeRule rule = new AccountCodeRule("-", 2, 3, 1);

        assertThat(rule.levelOf("1002")).isEqualTo(1);
        assertThat(rule.levelOf("1002-01")).isEqualTo(2);
        assertThat(rule.levelOf("1002-01-003")).isEqualTo(3);
        assertThat(rule.levelOf("1002-01-003-4")).isEqualTo(4);
        assertThat(rule.parentCode("1002-01-003")).contains("1002-01");
        assertThat(rule.parentCode("1002")).isEmpty();
        assertThat(rule.levelOf("1002.01")).isZero();
        assertThat(rule.levelOf("1002-1")).isZero();
    }

    @Test
    void rejectsUnsafeRules() {
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountCodeRule("/", 2, 2, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountCodeRule(".", 0, 2, 2));
        assertThatIllegalArgumentException().isThrownBy(() -> new AccountCodeRule(".", 8, 8, 9));
    }
}
