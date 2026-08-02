package com.example.accounting.ledger;

import com.example.accounting.shared.web.ApiProblemException;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/accounting-standards")
public class AccountingStandardController {

    private final AccountingStandardCatalog catalog;

    public AccountingStandardController(AccountingStandardCatalog catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    public List<AccountingStandard.Package> list() {
        return catalog.list();
    }

    @GetMapping("/{code}/versions/{version}")
    public AccountingStandard.Package get(@PathVariable String code, @PathVariable String version) {
        return catalog.find(code, version).orElseThrow(() -> new ApiProblemException(
                404, "ACCOUNTING_STANDARD_NOT_FOUND", "Accounting standard not found",
                "The requested accounting standard version is not installed", false));
    }
}
