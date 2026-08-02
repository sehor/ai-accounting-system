package com.example.accounting.ledger;

import java.util.Optional;

public record AccountCodeRule(int level2Width, int level3Width, int level4Width) {

    public static final AccountCodeRule DEFAULT = new AccountCodeRule(2, 2, 2);

    public AccountCodeRule {
        if (level2Width < 1 || level2Width > 8
                || level3Width < 1 || level3Width > 8
                || level4Width < 1 || level4Width > 8
                || 4 + level2Width + level3Width + level4Width > 32) {
            throw new IllegalArgumentException("account segment widths are invalid");
        }
    }

    public int levelOf(String code) {
        if (code == null || !code.matches("\\d+")) {
            return 0;
        }
        int length = 4;
        int[] childWidths = {level2Width, level3Width, level4Width};
        for (int level = 1; level <= 4; level++) {
            if (code.length() == length) {
                return level;
            }
            if (level < 4) {
                length += childWidths[level - 1];
            }
        }
        return 0;
    }

    public Optional<String> parentCode(String code) {
        int level = levelOf(code);
        if (level <= 1) {
            return Optional.empty();
        }
        int parentLength = 4;
        int[] childWidths = {level2Width, level3Width, level4Width};
        for (int index = 0; index < level - 2; index++) {
            parentLength += childWidths[index];
        }
        return Optional.of(code.substring(0, parentLength));
    }
}
