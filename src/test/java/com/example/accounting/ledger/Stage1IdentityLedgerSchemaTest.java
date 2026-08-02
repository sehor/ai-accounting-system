package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class Stage1IdentityLedgerSchemaTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsIdentityAndLedgerTablesWithDocumentedBoundaries() {
        assertThat(tableExists("app_user")).isTrue();
        assertThat(tableExists("ledger")).isTrue();
        assertThat(tableExists("ledger_membership")).isTrue();

        assertThat(columnsOf("app_user")).contains("issuer", "subject", "display_name", "email", "status");
        assertThat(columnsOf("ledger")).contains(
                "name",
                "accounting_standard_code",
                "accounting_standard_version",
                "base_currency",
                "start_date",
                "approval_enabled",
                "status",
                "created_at",
                "created_by",
                "updated_at",
                "updated_by",
                "version",
                "deleted_at");
        assertThat(columnsOf("ledger_membership")).contains("ledger_id", "user_id", "role", "status");
    }

    @Test
    void enforcesIdentityAndMembershipUniqueness() {
        assertThat(constraintExists("app_user", "uk_app_user_issuer_subject")).isTrue();
        assertThat(indexExists("ux_app_user_local_username_ci")).isTrue();
        assertThat(constraintExists("ledger_membership", "uk_ledger_membership_ledger_user")).isTrue();
        assertThat(indexExists("ix_ledger_membership_user_status")).isTrue();
        assertThat(indexExists("ix_ledger_membership_ledger_status")).isTrue();
    }

    private boolean tableExists(String tableName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from information_schema.tables "
                        + "where table_schema = 'public' and table_name = ?)",
                Boolean.class,
                tableName));
    }

    private Set<String> columnsOf(String tableName) {
        return Set.copyOf(jdbcTemplate.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_schema = 'public' and table_name = ?",
                String.class,
                tableName));
    }

    private boolean constraintExists(String tableName, String constraintName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from information_schema.table_constraints "
                        + "where table_schema = 'public' and table_name = ? and constraint_name = ?)",
                Boolean.class,
                tableName,
                constraintName));
    }

    private boolean indexExists(String indexName) {
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                "select exists (select 1 from pg_indexes where schemaname = 'public' and indexname = ?)",
                Boolean.class,
                indexName));
    }
}
