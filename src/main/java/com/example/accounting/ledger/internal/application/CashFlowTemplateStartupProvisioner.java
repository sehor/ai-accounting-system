package com.example.accounting.ledger.internal.application;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/**
 * Startup provisioning of the statutory cash flow template for every existing
 * ledger.  Runs after the report formula migration so canonical balance sheet /
 * income statement snapshots exist first; failures propagate and block
 * application readiness (a reserved cash flow item code occupied by a custom
 * item must never be silently overwritten).
 */
@Component
public class CashFlowTemplateStartupProvisioner implements ApplicationRunner {

    private final CashFlowTemplateProvisioner provisioner;

    public CashFlowTemplateStartupProvisioner(CashFlowTemplateProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @Override
    public void run(ApplicationArguments args) {
        provisioner.provisionAll();
    }
}
