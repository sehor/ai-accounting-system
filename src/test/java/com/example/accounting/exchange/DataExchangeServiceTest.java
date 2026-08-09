package com.example.accounting.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

class DataExchangeServiceTest {

    private final LedgerService ledgers = mock(LedgerService.class);
    private final VoucherService vouchers = mock(VoucherService.class);
    private final KingdeeExchange service = new KingdeeExchange(ledgers, vouchers);

    @Test
    void importsANativeKingdeeVoucherListAndNormalizesNegativeAmounts() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        when(ledgers.periodId(ledgerId, "2026-06")).thenReturn(periodId);
        when(ledgers.accountId(eq(ledgerId), anyString()))
                .thenAnswer(call -> UUID.nameUUIDFromBytes(call.<String>getArgument(1).getBytes()));
        byte[] workbook;
        try (var source = new HSSFWorkbook(); var output = new ByteArrayOutputStream()) {
            var sheet = source.createSheet("凭证列表#2018年第1期 至 2026年第6期");
            sheet.createRow(0).createCell(0).setCellValue("凭证列表");
            sheet.createRow(1).createCell(0).setCellValue("深圳市聚芯电科技有限公司");
            var header = sheet.createRow(2);
            List.of("日期", "凭证字号", "摘要", "科目", "借方金额", "贷方金额", "制单人", "审核人")
                    .forEach(value -> header.createCell(header.getLastCellNum() < 0 ? 0 : header.getLastCellNum())
                            .setCellValue(value));
            var debit = sheet.createRow(3);
            debit.createCell(0).setCellValue("2026-06-30");
            debit.createCell(1).setCellValue("记-9");
            debit.createCell(2).setCellValue("结转本期损益");
            debit.createCell(3).setCellValue("3103 本年利润");
            debit.createCell(4).setCellValue(100);
            var credit = sheet.createRow(4);
            credit.createCell(2).setCellValue("结转本期损益");
            credit.createCell(3).setCellValue("54010001 主营业务成本_集成电路销售成本");
            credit.createCell(5).setCellValue(150);
            var negativeCredit = sheet.createRow(5);
            negativeCredit.createCell(2).setCellValue("结转本期损益");
            negativeCredit.createCell(3).setCellValue("56030002 财务费用_利息");
            negativeCredit.createCell(5).setCellValue(-50);
            source.write(output);
            workbook = output.toByteArray();
        }

        KingdeeExchange.ImportResult result = service.importKingdee(
                actorId, ledgerId, "native", workbook.length, new ByteArrayInputStream(workbook));

        assertThat(result).isEqualTo(new KingdeeExchange.ImportResult(1, 3));
        ArgumentCaptor<VoucherRequests.Create> request = ArgumentCaptor.forClass(VoucherRequests.Create.class);
        verify(vouchers).create(eq(actorId), eq(ledgerId), request.capture(), eq("native:1"));
        assertThat(request.getValue().voucherType()).isEqualTo("记");
        assertThat(request.getValue().voucherNumber()).isEqualTo("9");
        assertThat(request.getValue().lines()).extracting(VoucherRequests.Line::side)
                .containsExactly("DEBIT", "CREDIT", "DEBIT");
        assertThat(request.getValue().lines()).extracting(VoucherRequests.Line::originalAmount)
                .containsExactly(new BigDecimal("100"), new BigDecimal("150"), new BigDecimal("50"));
    }

    @Test
    void importsTheProvidedKingdeeWorkbookAsGroupedDraftVouchers() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID periodId = UUID.randomUUID();
        when(ledgers.periodId(ledgerId, "2026-06")).thenReturn(periodId);
        when(ledgers.accountId(eq(ledgerId), anyString()))
                .thenAnswer(call -> UUID.nameUUIDFromBytes(call.<String>getArgument(1).getBytes()));

        KingdeeExchange.ImportResult result;
        Path sample = Path.of("src/main/resources/template/jindie.xlsx");
        try (var input = Files.newInputStream(sample)) {
            result = service.importKingdee(actorId, ledgerId, "upload-1", Files.size(sample), input);
        }

        assertThat(result).isEqualTo(new KingdeeExchange.ImportResult(12, 29));
        ArgumentCaptor<VoucherRequests.Create> requests = ArgumentCaptor.forClass(VoucherRequests.Create.class);
        verify(vouchers, times(12)).create(eq(actorId), eq(ledgerId), requests.capture(), any());
        VoucherRequests.Create first = requests.getAllValues().getFirst();
        assertThat(first.voucherDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(first.voucherType()).isEqualTo("记");
        assertThat(first.voucherNumber()).isEqualTo("504");
        assertThat(first.lines()).extracting(VoucherRequests.Line::side)
                .containsExactly("DEBIT", "CREDIT");
        assertThat(first.lines()).extracting(VoucherRequests.Line::currency)
                .containsOnly("CNY");
    }

    @Test
    void exportsVouchersUsingTheProvidedKingdeeLayout() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID debitAccount = UUID.randomUUID();
        UUID creditAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(debitAccount, ledgerId, "10020001", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE"),
                new LedgerResponses.Account(creditAccount, ledgerId, "22410090", "其他应付款",
                        "LIABILITY", "CREDIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                new VoucherResponses.Voucher(UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                        LocalDate.of(2026, 7, 1), "记", "1", "支付往来款", "DRAFT", false, 1,
                        List.of(
                                line(debitAccount, 1, "DEBIT"),
                                line(creditAccount, 2, "CREDIT")))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet("AccountEntries");
            var format = new DataFormatter();
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(format.formatCellValue(sheet.getRow(0).getCell(0))).isEqualTo("日期");
            assertThat(format.formatCellValue(sheet.getRow(0).getCell(23))).isEqualTo("汇率");
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(0))).isEqualTo("2026-07-01");
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(6))).isEqualTo("10020001");
            assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(123.45);
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(22))).isEqualTo("RMB");
            assertThat(sheet.getRow(2).getCell(9).getNumericCellValue()).isEqualTo(123.45);
        }
    }

    @Test
    void keepsEveryVoucherOnOneBackgroundStripe() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID debitAccount = UUID.randomUUID();
        UUID creditAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(debitAccount, ledgerId, "1002", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE"),
                new LedgerResponses.Account(creditAccount, ledgerId, "2241", "其他应付款",
                        "LIABILITY", "CREDIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 1), "1", "三行凭证",
                        line(debitAccount, 1, "DEBIT"),
                        line(debitAccount, 2, "DEBIT"),
                        line(creditAccount, 3, "CREDIT")),
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "2", "两行凭证",
                        line(debitAccount, 1, "DEBIT"),
                        line(creditAccount, 2, "CREDIT"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet("AccountEntries");
            short firstStripe = sheet.getRow(1).getCell(0).getCellStyle().getFillForegroundColor();
            short secondStripe = sheet.getRow(4).getCell(0).getCellStyle().getFillForegroundColor();
            assertThat(sheet.getRow(2).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(firstStripe);
            assertThat(sheet.getRow(3).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(firstStripe);
            assertThat(sheet.getRow(5).getCell(0).getCellStyle().getFillForegroundColor())
                    .isEqualTo(secondStripe);
            assertThat(secondStripe).isNotEqualTo(firstStripe);
        }
    }

    @Test
    void mergesSimilarVouchersWithinTheSamePeriodWhenRequested() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID payableAccount = UUID.randomUUID();
        UUID bankAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(payableAccount, ledgerId, "2202", "应付账款",
                        "LIABILITY", "CREDIT", "ACTIVE"),
                new LedgerResponses.Account(bankAccount, ledgerId, "1002", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "1", "支付供应商甲",
                        line(bankAccount, 1, "CREDIT", "100.00", "支付供应商甲"),
                        line(payableAccount, 2, "DEBIT", "100.00", "支付供应商甲")),
                voucher(ledgerId, LocalDate.of(2026, 7, 18), "2", "支付供应商乙",
                        line(bankAccount, 1, "CREDIT", "250.00", "支付供应商乙"),
                        line(payableAccount, 2, "DEBIT", "250.00", "支付供应商乙"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet("AccountEntries");
            var format = new DataFormatter();
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(0))).isEqualTo("2026-07-18");
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(2))).isEqualTo("3");
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(6))).isEqualTo("2202");
            assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(350.0);
            assertThat(format.formatCellValue(sheet.getRow(2).getCell(6))).isEqualTo("1002");
            assertThat(sheet.getRow(2).getCell(9).getNumericCellValue()).isEqualTo(350.0);
        }
    }

    @Test
    void ignoresVoucherTypeWhenApplyingBusinessMergeRules() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID payableAccount = UUID.randomUUID();
        UUID bankAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(payableAccount, ledgerId, "2202", "应付账款",
                        "LIABILITY", "CREDIT", "ACTIVE"),
                new LedgerResponses.Account(bankAccount, ledgerId, "1002", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "记", "1", "支付供应商甲",
                        line(payableAccount, 1, "DEBIT", "100.00", "支付供应商甲"),
                        line(bankAccount, 2, "CREDIT", "100.00", "支付供应商甲")),
                voucher(ledgerId, LocalDate.of(2026, 7, 18), "转", "2", "支付供应商乙",
                        line(payableAccount, 1, "DEBIT", "250.00", "支付供应商乙"),
                        line(bankAccount, 2, "CREDIT", "250.00", "支付供应商乙"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet("AccountEntries");
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
        }
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "收款-主营,应收账款,CREDIT,DEBIT",
            "付款-日常,其他应付款,DEBIT,CREDIT",
            "付款-主营,应付账款,DEBIT,CREDIT",
            "银行费用,财务费用,DEBIT,CREDIT"
    })
    void mergesConfiguredFirstLevelAccountPatterns(
            String ruleName, String businessAccountName,
            String businessSide, String bankSide) throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID businessRoot = UUID.randomUUID();
        UUID businessAccount = UUID.randomUUID();
        UUID bankRoot = UUID.randomUUID();
        UUID bankAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                account(businessRoot, ledgerId, "2000", businessAccountName, null, 1),
                account(businessAccount, ledgerId, "200001", businessAccountName + "明细", businessRoot, 2),
                account(bankRoot, ledgerId, "1002", "银行存款", null, 1),
                account(bankAccount, ledgerId, "100201", "银行存款明细", bankRoot, 2)));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "1", ruleName,
                        line(businessAccount, 1, businessSide, "100.00", ruleName),
                        line(bankAccount, 2, bankSide, "100.00", ruleName)),
                voucher(ledgerId, LocalDate.of(2026, 7, 18), "2", ruleName,
                        line(businessAccount, 1, businessSide, "250.00", ruleName),
                        line(bankAccount, 2, bankSide, "250.00", ruleName))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            var sheet = workbook.getSheet("AccountEntries");
            var format = new DataFormatter();
            String expectedDebitCode = "DEBIT".equals(businessSide) ? "200001" : "100201";
            String expectedCreditCode = "CREDIT".equals(businessSide) ? "200001" : "100201";
            assertThat(sheet.getLastRowNum()).isEqualTo(2);
            assertThat(format.formatCellValue(sheet.getRow(1).getCell(6))).isEqualTo(expectedDebitCode);
            assertThat(sheet.getRow(1).getCell(8).getNumericCellValue()).isEqualTo(350.0);
            assertThat(format.formatCellValue(sheet.getRow(2).getCell(6))).isEqualTo(expectedCreditCode);
            assertThat(sheet.getRow(2).getCell(9).getNumericCellValue()).isEqualTo(350.0);
        }
    }

    @Test
    void doesNotMergeUnconfiguredSalaryPayments() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID salaryAccount = UUID.randomUUID();
        UUID bankAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(salaryAccount, ledgerId, "2211", "应付职工薪酬",
                        "LIABILITY", "CREDIT", "ACTIVE"),
                new LedgerResponses.Account(bankAccount, ledgerId, "1002", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "1", "发放一组工资",
                        line(salaryAccount, 1, "DEBIT", "100.00", "发放一组工资"),
                        line(bankAccount, 2, "CREDIT", "100.00", "发放一组工资")),
                voucher(ledgerId, LocalDate.of(2026, 7, 18), "2", "发放二组工资",
                        line(salaryAccount, 1, "DEBIT", "250.00", "发放二组工资"),
                        line(bankAccount, 2, "CREDIT", "250.00", "发放二组工资"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            assertThat(workbook.getSheet("AccountEntries").getLastRowNum()).isEqualTo(4);
        }
    }

    @Test
    void doesNotMergeSimilarVouchersAcrossPeriods() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID payableAccount = UUID.randomUUID();
        UUID bankAccount = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(payableAccount, ledgerId, "2202", "应付账款",
                        "LIABILITY", "CREDIT", "ACTIVE"),
                new LedgerResponses.Account(bankAccount, ledgerId, "1002", "银行存款",
                        "ASSET", "DEBIT", "ACTIVE")));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 31), "1", "七月采购款",
                        line(payableAccount, 1, "DEBIT", "100.00", "七月采购款"),
                        line(bankAccount, 2, "CREDIT", "100.00", "七月采购款")),
                voucher(ledgerId, LocalDate.of(2026, 8, 1), "1", "八月采购款",
                        line(payableAccount, 1, "DEBIT", "200.00", "八月采购款"),
                        line(bankAccount, 2, "CREDIT", "200.00", "八月采购款"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            assertThat(workbook.getSheet("AccountEntries").getLastRowNum()).isEqualTo(4);
        }
    }

    @Test
    void doesNotMergeTransactionsFromDifferentBanks() throws Exception {
        UUID actorId = UUID.randomUUID();
        UUID ledgerId = UUID.randomUUID();
        UUID payableAccount = UUID.randomUUID();
        UUID bankRoot = UUID.randomUUID();
        UUID firstBank = UUID.randomUUID();
        UUID secondBank = UUID.randomUUID();
        when(ledgers.listAccounts(actorId, ledgerId)).thenReturn(List.of(
                new LedgerResponses.Account(payableAccount, ledgerId, "2202", "应付账款",
                        "LIABILITY", "CREDIT", "ACTIVE"),
                account(bankRoot, ledgerId, "1002", "银行存款", null, 1),
                account(firstBank, ledgerId, "100201", "工商银行", bankRoot, 2),
                account(secondBank, ledgerId, "100202", "建设银行", bankRoot, 2)));
        when(vouchers.list(actorId, ledgerId, 500, 0)).thenReturn(List.of(
                voucher(ledgerId, LocalDate.of(2026, 7, 2), "1", "工行支付采购款",
                        line(payableAccount, 1, "DEBIT", "100.00", "工行支付采购款"),
                        line(firstBank, 2, "CREDIT", "100.00", "工行支付采购款")),
                voucher(ledgerId, LocalDate.of(2026, 7, 18), "2", "建行支付采购款",
                        line(payableAccount, 1, "DEBIT", "250.00", "建行支付采购款"),
                        line(secondBank, 2, "CREDIT", "250.00", "建行支付采购款"))));
        when(vouchers.list(actorId, ledgerId, 500, 500)).thenReturn(List.of());

        byte[] output = service.exportKingdee(actorId, ledgerId, true);

        try (var workbook = WorkbookFactory.create(new ByteArrayInputStream(output))) {
            assertThat(workbook.getSheet("AccountEntries").getLastRowNum()).isEqualTo(4);
        }
    }

    @Test
    void rejectsFieldsThatTheVoucherModelCannotPreserve() throws Exception {
        byte[] workbookBytes;
        try (var input = Files.newInputStream(Path.of("src/main/resources/template/jindie.xlsx"));
             var workbook = WorkbookFactory.create(input);
             var output = new ByteArrayOutputStream()) {
            workbook.getSheet("AccountEntries").getRow(1).getCell(10).setCellValue("客户A");
            workbook.write(output);
            workbookBytes = output.toByteArray();
        }

        assertThatThrownBy(() -> service.importKingdee(UUID.randomUUID(), UUID.randomUUID(), null,
                workbookBytes.length, new ByteArrayInputStream(workbookBytes)))
                .isInstanceOfSatisfying(ApiProblemException.class,
                        problem -> assertThat(problem.code()).isEqualTo("KINGDEE_FIELD_UNSUPPORTED"));
        verifyNoInteractions(vouchers);
    }

    private VoucherResponses.Line line(UUID accountId, int lineNo, String side) {
        return new VoucherResponses.Line(UUID.randomUUID(), lineNo, accountId, side, "CNY",
                new BigDecimal("123.45"), BigDecimal.ONE, new BigDecimal("123.45"), "支付往来款");
    }

    private VoucherResponses.Line line(
            UUID accountId, int lineNo, String side, String amount, String summary) {
        BigDecimal value = new BigDecimal(amount);
        return new VoucherResponses.Line(UUID.randomUUID(), lineNo, accountId, side, "CNY",
                value, BigDecimal.ONE, value, summary);
    }

    private VoucherResponses.Voucher voucher(
            UUID ledgerId, LocalDate date, String number, String summary, VoucherResponses.Line... lines) {
        return voucher(ledgerId, date, "记", number, summary, lines);
    }

    private VoucherResponses.Voucher voucher(
            UUID ledgerId, LocalDate date, String type, String number, String summary,
            VoucherResponses.Line... lines) {
        return new VoucherResponses.Voucher(UUID.randomUUID(), ledgerId, UUID.randomUUID(),
                date, type, number, summary, "POSTED", false, 1, List.of(lines));
    }

    private LedgerResponses.Account account(
            UUID id, UUID ledgerId, String code, String name, UUID parentId, int level) {
        return new LedgerResponses.Account(id, ledgerId, code, name, "ASSET", "DEBIT", "ACTIVE",
                parentId, level, true, false, false, false, false, 0,
                false, null, false, null, List.of());
    }
}
