package com.example.accounting.voucher.internal.application;

import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRenumbering;
import com.example.accounting.voucher.internal.port.VoucherRenumberRepository.Row;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class VoucherRenumberPlanner {
    private static final BigInteger MAX = BigInteger.valueOf(Long.MAX_VALUE - 1);

    record Plan(List<VoucherRenumbering.Change> changes, String first, String last, long temporaryStart) { }

    static Plan plan(VoucherRenumbering.Request request, List<Row> rows) {
        if (request.periodId() == null || request.mode() == null || request.startNumber() == null
                || !request.startNumber().matches("[1-9][0-9]{0,18}")) {
            throw invalid("VOUCHER_RENUMBER_PARAMETERS_INVALID", "请选择期间、整理方式，并填写有效的正整数起始号");
        }
        BigInteger start = new BigInteger(request.startNumber());
        BigInteger largest = BigInteger.ZERO;
        for (Row row : rows) {
            if (!"记".equals(row.type()) || row.number() == null || !row.number().matches("[0-9]+")) {
                throw invalid("VOUCHER_RENUMBER_LEGACY_NUMBER", "该期间存在异常字号，请先完成凭证字号规范化");
            }
            largest = largest.max(new BigInteger(row.number()));
        }
        BigInteger last = start.add(BigInteger.valueOf(Math.max(0, rows.size() - 1)));
        if (start.compareTo(MAX) > 0 || last.compareTo(MAX) > 0 || largest.compareTo(MAX) > 0) {
            throw invalid("VOUCHER_RENUMBER_NUMBER_RANGE", "凭证号超出可用范围，请减小起始号");
        }
        Comparator<Row> byNumber = Comparator.comparing(row -> new BigInteger(row.number()));
        Comparator<Row> order = request.mode() == VoucherRenumbering.Mode.DATE_ORDER
                ? Comparator.comparing(Row::date).thenComparing(byNumber) : byNumber;
        List<Row> sorted = rows.stream().sorted(order.thenComparing(Row::createdAt).thenComparing(Row::id)).toList();
        List<VoucherRenumbering.Change> changes = new ArrayList<>();
        for (int index = 0; index < sorted.size(); index++) {
            Row row = sorted.get(index);
            String number = start.add(BigInteger.valueOf(index)).toString();
            if (!row.number().equals(number)) {
                changes.add(new VoucherRenumbering.Change(row.id(), row.date(), row.summary(), row.number(), number));
            }
        }
        BigInteger temporaryStart = largest.max(last).add(BigInteger.ONE);
        if (!changes.isEmpty() && temporaryStart.add(BigInteger.valueOf(changes.size() - 1)).compareTo(MAX) > 0) {
            throw invalid("VOUCHER_RENUMBER_NUMBER_RANGE", "凭证号超出整理可用范围，请减小起始号");
        }
        return new Plan(List.copyOf(changes), rows.isEmpty() ? null : start.toString(),
                rows.isEmpty() ? null : last.toString(), changes.isEmpty() ? 0 : temporaryStart.longValueExact());
    }

    private static ApiProblemException invalid(String code, String detail) {
        return new ApiProblemException(422, code, "无法整理凭证号", detail, false);
    }
}
