package com.example.accounting.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class AccountingStandardCatalogTest {

    @Test
    void loadsVersionedSmeAndCasPackagesWithTreesAndControls() {
        AccountingStandardCatalog catalog = new AccountingStandardCatalog(
                new ObjectMapper().findAndRegisterModules());

        assertThat(catalog.list()).extracting(AccountingStandard.Package::key)
                .containsExactly("CAS/2006-18", "SME/2011-17");
        AccountingStandard.Package sme = catalog.find("SME", "2011-17").orElseThrow();
        assertThat(sme.accounts()).isNotEmpty();
        assertThat(sme.accounts()).allMatch(account -> account.code().matches("\\d{4}"));
        assertThat(sme.standardAccountKeys()).extracting(AccountingStandard.StandardAccountKey::key)
                .contains("ASSET.CASH", "EXPENSE.ADMINISTRATIVE");
        assertThat(sme.accounts()).allMatch(account -> account.standardAccountKey() != null);
        assertThat(catalog.legacyCodeMatches("SME", "v1", "5601"))
                .containsExactlyInAnyOrder("EXPENSE.SELLING", "EXPENSE.ADMINISTRATIVE");
        assertThat(catalog.resolveLegacyCode("SME", "v1", "5601")).isEmpty();
        assertThat(sme.formulas()).extracting(AccountingStandard.Formula::code)
                .contains("BALANCE_SHEET", "INCOME_STATEMENT");
        assertThat(sme.cashFlowItems()).isNotEmpty();
        assertThat(sme.dimensionTypes()).extracting(AccountingStandard.DimensionType::code)
                .contains("CUSTOMER", "SUPPLIER", "DEPARTMENT", "PERSON", "PROJECT");
        assertThat(catalog.find("SME", "missing")).isEmpty();
    }
}
