package com.example.accounting.voucher.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository.Row;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class VoucherRenumberPlannerTest {
    private final UUID period = UUID.randomUUID();

    @Test
    void dateOrderAlsoClosesGapsAndUsesNumericOrderWithinDay() {
        Row two = row("2", 10), ten = row("10", 10), four = row("4", 2);
        var plan = VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "1"), List.of(ten, two, four));
        assertThat(plan.changes()).extracting(VoucherRenumbering.Change::voucherId).containsExactly(four.id(), ten.id());
        assertThat(plan.changes()).extracting(VoucherRenumbering.Change::newNumber).containsExactly("1", "3");
        assertThat(plan.temporaryStart()).isGreaterThan(10);
    }

    @Test
    void numberOrderIgnoresDateAndSupportsCustomStartingNumber() {
        var plan = VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.NUMBER_ORDER, "20"),
                List.of(row("10", 1), row("2", 20), row("6", 15)));
        assertThat(plan.changes()).extracting(VoucherRenumbering.Change::oldNumber).containsExactly("2", "6", "10");
        assertThat(plan.changes()).extracting(VoucherRenumbering.Change::newNumber).containsExactly("20", "21", "22");
        assertThat(plan.temporaryStart()).isEqualTo(23);
    }

    @Test
    void emptyAndAlreadyContiguousPeriodsProduceNoChanges() {
        var empty = VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "1"), List.of());
        assertThat(empty.changes()).isEmpty();
        assertThat(empty.first()).isNull();
        assertThat(empty.last()).isNull();
        assertThat(VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "1"),
                List.of(row("1", 1), row("2", 2))).changes()).isEmpty();
    }

    @Test
    void handlesWholePeriodBeyondListPagination() {
        var rows = IntStream.rangeClosed(1, 650).mapToObj(i -> row(Integer.toString(i * 2), 1)).toList();
        var plan = VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "1"), rows);
        assertThat(plan.changes()).hasSize(650);
        assertThat(plan.last()).isEqualTo("650");
        assertThat(plan.temporaryStart()).isEqualTo(1301);
    }

    @Test
    void rejectsInvalidNumbersAndOverflowBeforeAnyWrite() {
        for (String start : List.of("0", "-1", "1.5", "9223372036854775807")) {
            assertThatThrownBy(() -> VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, start), List.of()))
                    .isInstanceOf(ApiProblemException.class);
        }
        assertThatThrownBy(() -> VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "1"),
                List.of(row("PC-EXPENSE", 1)))).isInstanceOf(ApiProblemException.class);
        assertThatThrownBy(() -> VoucherRenumberPlanner.plan(request(VoucherRenumbering.Mode.DATE_ORDER, "9223372036854775806"),
                List.of(row("1", 1)))).isInstanceOf(ApiProblemException.class);
    }

    private VoucherRenumbering.Request request(VoucherRenumbering.Mode mode, String start) {
        return new VoucherRenumbering.Request(period, mode, start);
    }

    private Row row(String number, int day) {
        return new Row(UUID.randomUUID(), LocalDate.of(2026, 1, day), "记", number, "test", "POSTED", false,
                2, 3, null, null, OffsetDateTime.parse("2026-01-01T00:00:00Z"));
    }
}
