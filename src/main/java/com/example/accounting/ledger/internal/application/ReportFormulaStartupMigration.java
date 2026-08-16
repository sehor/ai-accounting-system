package com.example.accounting.ledger.internal.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Runs the idempotent report formula migration at startup.  A failing migration
 * propagates out of the runner and prevents the application from becoming ready.
 */
@Component
public class ReportFormulaStartupMigration implements ApplicationRunner {

    private final ReportFormulaMigrationService migration;

    public ReportFormulaStartupMigration(ReportFormulaMigrationService migration) {
        this.migration = migration;
    }

    @Override
    public void run(ApplicationArguments args) {
        migration.migrateAll();
    }
}
