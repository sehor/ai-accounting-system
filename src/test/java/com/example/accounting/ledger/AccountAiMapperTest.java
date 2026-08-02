package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class AccountAiMapperTest {

    @Test
    void degradesWithoutConfigurationOrHttps() {
        assertThat(new AccountAiMapper("", "").suggest(List.of(), List.of()).status())
                .isEqualTo("NOT_CONFIGURED");
        assertThat(new AccountAiMapper("http://localhost/mapping", "").suggest(
                List.of(new AccountAiMapper.Source(1, "1001", "现金")), List.of()).status())
                .isEqualTo("FAILED");
    }
}
