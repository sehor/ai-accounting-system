package com.example.accounting.shared.accounting;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Canonical, immutable identity for a set of auxiliary dimension values. */
public final class DimensionCombinationKey {

    private static final String VERSION_PREFIX = "v1;";

    private DimensionCombinationKey() {
    }

    public static Result of(Collection<Dimension> dimensions) {
        Objects.requireNonNull(dimensions, "dimensions");
        List<Dimension> ordered = dimensions.stream()
                .map(dimension -> Objects.requireNonNull(dimension, "dimension"))
                .peek(DimensionCombinationKey::requireIds)
                .sorted(Comparator.comparing(dimension -> dimension.dimensionTypeId().toString()))
                .toList();
        for (int index = 1; index < ordered.size(); index++) {
            if (ordered.get(index - 1).dimensionTypeId().equals(ordered.get(index).dimensionTypeId())) {
                throw new IllegalArgumentException("Each dimension type may appear only once");
            }
        }
        String canonicalKey = VERSION_PREFIX + ordered.stream()
                .map(dimension -> dimension.dimensionTypeId() + "=" + dimension.dimensionValueId() + ";")
                .reduce("", String::concat);
        return new Result(canonicalKey, md5(canonicalKey));
    }

    private static void requireIds(Dimension dimension) {
        Objects.requireNonNull(dimension.dimensionTypeId(), "dimensionTypeId");
        Objects.requireNonNull(dimension.dimensionValueId(), "dimensionValueId");
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("MD5 must be available in the Java runtime", exception);
        }
    }

    public record Dimension(UUID dimensionTypeId, UUID dimensionValueId) {
    }

    public record Result(String canonicalKey, String dimensionKey) {
    }
}
