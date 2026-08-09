package com.example.accounting.test;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class IsolatedDatabaseContextTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void usesAnEphemeralNonPublicSchema() {
        String schema = jdbc.queryForObject("select current_schema()", String.class);

        assertThat(schema).startsWith("test_").isNotEqualTo("public");
    }
}
