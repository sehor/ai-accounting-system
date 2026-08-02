package com.example.accounting.ledger;

import java.util.Optional;
import java.util.regex.Pattern;

public record AccountCodeRule(String separator, int level2Width, int level3Width, int level4Width) {

    public static final AccountCodeRule DEFAULT = new AccountCodeRule(".", 2, 2, 2);

    public AccountCodeRule {
        if (!".".equals(separator) && !"-".equals(separator)) {
            throw new IllegalArgumentException("separator must be . or -");
        }
        if (level2Width < 1 || level2Width > 8
                || level3Width < 1 || level3Width > 8
                || level4Width < 1 || level4Width > 8
                || 7 + level2Width + level3Width + level4Width > 32) {
            throw new IllegalArgumentException("account segment widths are invalid");
        }
    }

    public int levelOf(String code) {
        if (code == null) {
            return 0;
        }
        String[] segments = code.split(Pattern.quote(separator), -1);
        if (segments.length < 1 || segments.length > 4 || !segments[0].matches("\\d{4}")) {
            return 0;
        }
        int[] widths = {4, level2Width, level3Width, level4Width};
        for (int index = 1; index < segments.length; index++) {
            if (!segments[index].matches("\\d{" + widths[index] + "}")) {
                return 0;
            }
        }
        return segments.length;
    }

    public Optional<String> parentCode(String code) {
        int level = levelOf(code);
        if (level <= 1) {
            return Optional.empty();
        }
        return Optional.of(code.substring(0, code.lastIndexOf(separator)));
    }
}
