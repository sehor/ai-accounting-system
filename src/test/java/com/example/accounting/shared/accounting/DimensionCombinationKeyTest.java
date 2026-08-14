package com.example.accounting.shared.accounting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DimensionCombinationKeyTest {

    private static final UUID TYPE_A = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TYPE_B = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID VALUE_A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID VALUE_B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    @Test
    void createsTheEmptyCombination() {
        DimensionCombinationKey.Result result = DimensionCombinationKey.of(List.of());

        assertThat(result.canonicalKey()).isEqualTo("v1;");
        assertThat(result.dimensionKey()).isEqualTo("8341d3be2bc3a037d5da37cdcc3945aa");
    }

    @Test
    void sortsTypesIntoAStableCanonicalForm() {
        DimensionCombinationKey.Result result = DimensionCombinationKey.of(List.of(
                new DimensionCombinationKey.Dimension(TYPE_B, VALUE_B),
                new DimensionCombinationKey.Dimension(TYPE_A, VALUE_A)));

        assertThat(result.canonicalKey()).isEqualTo("v1;"
                + "00000000-0000-0000-0000-000000000001=10000000-0000-0000-0000-000000000001;"
                + "00000000-0000-0000-0000-000000000002=10000000-0000-0000-0000-000000000002;");
        assertThat(result.dimensionKey()).hasSize(32).matches("[0-9a-f]{32}");
    }

    @Test
    void distinguishesDifferentCombinations() {
        DimensionCombinationKey.Result first = DimensionCombinationKey.of(List.of(
                new DimensionCombinationKey.Dimension(TYPE_A, VALUE_A)));
        DimensionCombinationKey.Result second = DimensionCombinationKey.of(List.of(
                new DimensionCombinationKey.Dimension(TYPE_A, VALUE_B)));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void rejectsDuplicateDimensionTypes() {
        assertThatThrownBy(() -> DimensionCombinationKey.of(List.of(
                new DimensionCombinationKey.Dimension(TYPE_A, VALUE_A),
                new DimensionCombinationKey.Dimension(TYPE_A, VALUE_B))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Each dimension type may appear only once");
    }
}
