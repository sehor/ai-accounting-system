package com.example.accounting.ledger;

import com.example.accounting.ledger.formula.StandardFormulaValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class AccountingStandardCatalog {

    private static final Pattern STANDARD_ACCOUNT_KEY = Pattern.compile(
            "[A-Z][A-Z0-9]*(\\.[A-Z0-9_]+)+");

    private static final List<String> RESOURCES = List.of(
            "accounting-standards/SME/2011-17.json",
            "accounting-standards/CAS/2006-18.json");

    private final Map<String, AccountingStandard.Package> packages;

    public AccountingStandardCatalog() {
        this(new ObjectMapper().findAndRegisterModules());
    }

    public AccountingStandardCatalog(ObjectMapper objectMapper) {
        Map<String, AccountingStandard.Package> loaded = new LinkedHashMap<>();
        StandardFormulaValidator formulaValidator = new StandardFormulaValidator();
        for (String resource : RESOURCES) {
            AccountingStandard.Package standard = read(objectMapper, resource);
            validate(standard);
            formulaValidator.validateAll(standard);
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

    public Optional<AccountingStandard.Formula> formula(String code, String version, String formulaCode) {
        return find(code, version).flatMap(standard -> standard.formulas().stream()
                .filter(formula -> formulaCode.equals(formula.code())).findFirst());
    }

    public boolean containsStandardAccountKey(String code, String version, String key) {
        return find(code, normalizedVersion(code, version)).stream()
                .flatMap(standard -> standard.standardAccountKeys().stream())
                .anyMatch(candidate -> candidate.key().equals(key));
    }

    public Optional<String> packageAccountKey(String code, String version, String accountCode) {
        return find(code, normalizedVersion(code, version)).stream()
                .flatMap(standard -> standard.accounts().stream())
                .filter(account -> account.code().equals(accountCode))
                .map(AccountingStandard.Account::standardAccountKey).findFirst();
    }

    public Optional<String> resolveLegacyCode(String code, String version, String legacyCode) {
        List<String> matches = legacyCodeMatches(code, version, legacyCode);
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    public List<String> legacyCodeMatches(String code, String version, String legacyCode) {
        return find(code, normalizedVersion(code, version)).stream()
                .flatMap(standard -> standard.standardAccountKeys().stream())
                .filter(candidate -> candidate.legacyCodes().contains(legacyCode))
                .map(AccountingStandard.StandardAccountKey::key).distinct().toList();
    }

    private String normalizedVersion(String code, String version) {
        return "SME".equalsIgnoreCase(code) && "v1".equals(version) ? "2011-17" : version;
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
                || standard.standardAccountKeys() == null || standard.standardAccountKeys().isEmpty()
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
        Set<String> keys = standard.standardAccountKeys().stream()
                .map(AccountingStandard.StandardAccountKey::key).collect(Collectors.toSet());
        if (keys.size() != standard.standardAccountKeys().size()
                || keys.stream().anyMatch(key -> !STANDARD_ACCOUNT_KEY.matcher(key).matches())) {
            throw new IllegalStateException("invalid or duplicate standard account key in " + standard.key());
        }
        for (AccountingStandard.Account account : standard.accounts()) {
            if (!keys.contains(account.standardAccountKey())) {
                throw new IllegalStateException("unregistered account key in " + standard.key()
                        + ": " + account.code());
            }
            if (!AccountCategory.isValid(account.category())) {
                throw new IllegalStateException("invalid account category in " + standard.key()
                        + ": " + account.code() + "/" + account.category());
            }
            int level = standard.accountCodeRule().levelOf(account.code());
            if (level == 0 || (level == 1 && account.parentCode() != null)
                    || (level > 1 && !codes.contains(account.parentCode()))
                    || (level > 1 && !standard.accountCodeRule().parentCode(account.code())
                    .filter(account.parentCode()::equals).isPresent())) {
                throw new IllegalStateException("invalid account tree in " + standard.key());
            }
        }
        for (AccountingStandard.Formula formula : standard.formulas()) {
            validateFormulaAccountReferences(standard, formula.definition(), keys);
        }
    }

    private void validateFormulaAccountReferences(
            AccountingStandard.Package standard, com.fasterxml.jackson.databind.JsonNode node, Set<String> keys) {
        if (node.isObject()) {
            if (node.has("accounts")) {
                Set<String> operationKeys = new java.util.HashSet<>();
                for (com.fasterxml.jackson.databind.JsonNode reference : node.path("accounts")) {
                    if (!reference.hasNonNull("key") || reference.has("code") || reference.has("name")
                            || !keys.contains(reference.path("key").asText())
                            || !operationKeys.add(reference.path("key").asText())) {
                        throw new IllegalStateException("invalid formula account reference in " + standard.key());
                    }
                }
            }
            node.fields().forEachRemaining(entry ->
                    validateFormulaAccountReferences(standard, entry.getValue(), keys));
        } else if (node.isArray()) {
            node.forEach(child -> validateFormulaAccountReferences(standard, child, keys));
        }
    }
}
