package com.example.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

class AccountingModularityTest {

    @Test
    void modulesFollowTheDeclaredBoundaries() {
        ApplicationModules.of(AccountingApplication.class).verify();
    }
}
