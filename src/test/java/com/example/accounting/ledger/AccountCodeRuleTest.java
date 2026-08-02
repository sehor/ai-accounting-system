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
}
