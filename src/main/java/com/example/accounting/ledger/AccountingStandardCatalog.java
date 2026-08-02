package com.example.accounting.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class AccountingStandardCatalog {

    private static final List<String> RESOURCES = List.of(
            "accounting-standards/SME/2011-17.json",
            "accounting-standards/CAS/2006-18.json");

    private final Map<String, AccountingStandard.Package> packages;

    public AccountingStandardCatalog() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public AccountingStandardCatalog(ObjectMapper objectMapper) {
        Map<String, AccountingStandard.Package> loaded = new LinkedHashMap<>();
        for (String resource : RESOURCES) {
            AccountingStandard.Package standard = read(objectMapper, resource);
            validate(standard);
            if (loaded.put(standard.key(), standard) != null) {
                throw new IllegalStateException("duplicate accounting standard " + standard.key());
            }
        }
        packages = Map.copyOf(loaded);
    }

    public List<AccountingStandard.Package> list() {
        return packages.values().stream()
                .sorted(Comparator.comparing(AccountingStandard.Package::key))
                .toList();
    }

    public Optional<AccountingStandard.Package> find(String code, String version) {
        return Optional.ofNullable(packages.get(code + "/" + version));
    }

    private AccountingStandard.Package read(ObjectMapper objectMapper, String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream()) {
            return objectMapper.readValue(input, AccountingStandard.Package.class);
        } catch (IOException exception) {
            throw new IllegalStateException("cannot load accounting standard " + resource, exception);
        }
    }

    private void validate(AccountingStandard.Package standard) {
        if (standard.code() == null || standard.version() == null || standard.accounts() == null
                || standard.accounts().isEmpty() || standard.formulas() == null
                || standard.cashFlowItems() == null || standard.dimensionTypes() == null
                || standard.accountCodeRule() == null) {
            throw new IllegalStateException("incomplete accounting standard package");
        }
        Set<String> codes = standard.accounts().stream()
                .map(AccountingStandard.Account::code).collect(Collectors.toSet());
        if (codes.size() != standard.accounts().size()) {
            throw new IllegalStateException("duplicate account code in " + standard.key());
        }
        for (AccountingStandard.Account account : standard.accounts()) {
            int level = standard.accountCodeRule().levelOf(account.code());
            if (level == 0 || (level == 1 && account.parentCode() != null)
                    || (level > 1 && !codes.contains(account.parentCode()))
                    || (level > 1 && !standard.accountCodeRule().parentCode(account.code())
                    .filter(account.parentCode()::equals).isPresent())) {
                throw new IllegalStateException("invalid account tree in " + standard.key());
            }
        }
    }
}
