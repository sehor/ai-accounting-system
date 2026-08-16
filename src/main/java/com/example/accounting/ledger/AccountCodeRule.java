package com.example.accounting.ledger;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Schema(name = "AccountCodeRule", requiredProperties = {"level2Width", "level3Width", "level4Width"})
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

    public int childWidth(int parentLevel) {
        return switch (parentLevel) {
            case 1 -> level2Width;
            case 2 -> level3Width;
            case 3 -> level4Width;
            default -> throw new IllegalArgumentException("Parent level must be between 1 and 3, got: " + parentLevel);
        };
    }

    public String nextChildCode(String parentCode, Collection<String> existingSiblingCodes) {
        int parentLevel = levelOf(parentCode);
        if (parentLevel <= 0 || parentLevel >= 4) {
            throw new IllegalArgumentException("Parent account must be between level 1 and 3, but got level " + parentLevel + " for code " + parentCode);
        }
        int width = childWidth(parentLevel);
        int expectedLength = parentCode.length() + width;
        Set<Integer> usedNumbers = new HashSet<>();
        if (existingSiblingCodes != null) {
            for (String code : existingSiblingCodes) {
                if (code != null && code.startsWith(parentCode) && code.length() == expectedLength) {
                    String suffix = code.substring(parentCode.length());
                    try {
                        int val = Integer.parseInt(suffix);
                        if (val > 0) {
                            usedNumbers.add(val);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        }
        long maxVal = (long) Math.pow(10, width) - 1;
        for (int i = 1; i <= maxVal; i++) {
            if (!usedNumbers.contains(i)) {
                return parentCode + String.format(Locale.ROOT, "%0" + width + "d", i);
            }
        }
        throw new IllegalStateException("All child account codes under " + parentCode + " are exhausted (limit " + maxVal + ")");
    }
}
