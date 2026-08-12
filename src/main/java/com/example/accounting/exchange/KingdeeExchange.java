package com.example.accounting.exchange;

import com.example.accounting.ledger.LedgerResponses;
import com.example.accounting.ledger.LedgerService;
import com.example.accounting.shared.web.ApiProblemException;
import com.example.accounting.voucher.VoucherRequests;
import com.example.accounting.voucher.VoucherResponses;
import com.example.accounting.voucher.VoucherService;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class KingdeeExchange {

    private static final long MAX_FILE_SIZE = 10 * 1024 * 1024;
    private static final List<String> HEADERS = List.of(
            "日期", "凭证字", "凭证号", "附件数", "分录序号", "摘要", "科目代码", "科目名称",
            "借方金额", "贷方金额", "客户", "供应商", "职员", "项目", "部门", "存货", "是否限定",
            "自定义辅助核算类别", "自定义辅助核算编码", "数量", "单价", "原币金额", "币别", "汇率");
    private static final List<String> NATIVE_HEADERS = List.of(
            "日期", "凭证字号", "摘要", "科目", "借方金额", "贷方金额", "制单人", "审核人");

    private final LedgerService ledgers;
    private final VoucherService vouchers;
    private final DataFormatter formatter = new DataFormatter(Locale.ROOT);

    public KingdeeExchange(LedgerService ledgers, VoucherService vouchers) {
        this.ledgers = ledgers;
        this.vouchers = vouchers;
    }

    @Transactional
    public ImportResult importKingdee(UUID actorId, UUID ledgerId, String idempotencyKey,
                                      long fileSize, InputStream input) {
        if (fileSize <= 0 || fileSize > MAX_FILE_SIZE) {
            throw problem(413, "KINGDEE_FILE_SIZE_INVALID", "Invalid Kingdee file",
                    "The workbook must be between 1 byte and 10 MB");
        }
        if (idempotencyKey != null && idempotencyKey.trim().length() > 120) {
            throw problem(400, "IDEMPOTENCY_KEY_INVALID", "Invalid idempotency key",
                    "The idempotency key must not exceed 120 characters");
        }
        ParsedWorkbook parsed = parse(ledgerId, input);
        int index = 0;
        for (VoucherRequests.Create request : parsed.vouchers()) {
            String key = idempotencyKey == null || idempotencyKey.isBlank()
                    ? null : idempotencyKey.trim() + ":" + (++index);
            vouchers.create(actorId, ledgerId, request, key);
        }
        return new ImportResult(parsed.vouchers().size(), parsed.rowCount());
    }

    @Transactional(readOnly = true)
    public byte[] exportKingdee(UUID actorId, UUID ledgerId) {
        return exportKingdee(actorId, ledgerId, false);
    }

    @Transactional(readOnly = true)
    public byte[] exportKingdee(UUID actorId, UUID ledgerId, boolean mergeEntries) {
        Map<UUID, LedgerResponses.Account> accounts = ledgers.listAccounts(actorId, ledgerId).stream()
                .collect(Collectors.toMap(LedgerResponses.Account::id, account -> account));
        List<VoucherResponses.Voucher> all = new ArrayList<>();
        for (int offset = 0; ; offset += 500) {
            List<VoucherResponses.Voucher> page = vouchers.list(actorId, ledgerId, 500, offset);
            all.addAll(page);
            if (page.size() < 500) {
                break;
            }
        }
        all.sort(Comparator.comparing(VoucherResponses.Voucher::voucherDate)
                .thenComparing(VoucherResponses.Voucher::voucherType)
                .thenComparing(VoucherResponses.Voucher::voucherNumber));
        if (mergeEntries) {
            all = mergeSimilarVouchers(all, accounts);
            all.sort(Comparator.comparing(VoucherResponses.Voucher::voucherDate)
                    .thenComparing(VoucherResponses.Voucher::voucherType)
                    .thenComparing(VoucherResponses.Voucher::voucherNumber));
        }
        try (InputStream template = getClass().getResourceAsStream("/template/jindie.xlsx")) {
            if (template == null) {
                throw problem(500, "KINGDEE_TEMPLATE_MISSING", "Kingdee template missing",
                        "The Kingdee workbook template is not available");
            }
            try (Workbook workbook = WorkbookFactory.create(template);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                Sheet sheet = workbook.getSheet("AccountEntries");
                Row firstStripeRow = sheet.getRow(1);
                Row secondStripeRow = sheet.getRow(3);
                CellStyle[][] stripeStyles = {
                        cellStyles(firstStripeRow), cellStyles(secondStripeRow)
                };
                short[] stripeHeights = {firstStripeRow.getHeight(), secondStripeRow.getHeight()};
                int rowIndex = 1;
                int voucherIndex = 0;
                for (VoucherResponses.Voucher voucher : all) {
                    int stripeIndex = voucherIndex++ % stripeStyles.length;
                    List<VoucherResponses.Line> lines = voucher.lines().stream()
                            .sorted(Comparator.comparingInt(VoucherResponses.Line::lineNo)).toList();
                    for (VoucherResponses.Line line : lines) {
                        Row row = sheet.getRow(rowIndex);
                        if (row == null) {
                            row = sheet.createRow(rowIndex);
                        }
                        copyStyle(stripeStyles[stripeIndex], stripeHeights[stripeIndex], row);
                        write(row, voucher, line, account(accounts, line.accountId()));
                        rowIndex++;
                    }
                }
                for (int index = sheet.getLastRowNum(); index >= rowIndex; index--) {
                    Row row = sheet.getRow(index);
                    if (row != null) {
                        sheet.removeRow(row);
                    }
                }
                workbook.write(output);
                return output.toByteArray();
            }
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw problem(500, "KINGDEE_EXPORT_FAILED", "Kingdee export failed",
                    "The Kingdee workbook could not be generated");
        }
    }

    private List<VoucherResponses.Voucher> mergeSimilarVouchers(
            List<VoucherResponses.Voucher> source,
            Map<UUID, LedgerResponses.Account> accounts) {
        Map<YearMonth, List<VoucherResponses.Voucher>> byPeriod = new LinkedHashMap<>();
        for (VoucherResponses.Voucher voucher : source) {
            byPeriod.computeIfAbsent(YearMonth.from(voucher.voucherDate()), ignored -> new ArrayList<>())
                    .add(voucher);
        }
        List<VoucherResponses.Voucher> result = new ArrayList<>();
        for (List<VoucherResponses.Voucher> period : byPeriod.values()) {
            result.addAll(mergePeriod(period, accounts));
        }
        return result;
    }

    private List<VoucherResponses.Voucher> mergePeriod(
            List<VoucherResponses.Voucher> period,
            Map<UUID, LedgerResponses.Account> accounts) {
        Map<MergeGroupKey, List<VoucherResponses.Voucher>> groups = new LinkedHashMap<>();
        List<VoucherResponses.Voucher> unchanged = new ArrayList<>();
        for (VoucherResponses.Voucher voucher : period) {
            MergeCategory category = category(voucher, accounts);
            if (category == null) {
                unchanged.add(voucher);
                continue;
            }
            MergeGroupKey key = new MergeGroupKey(category, bankAccountIds(voucher, accounts));
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(voucher);
        }

        int nextVoucherNumber = period.stream()
                .map(VoucherResponses.Voucher::voucherNumber)
                .map(KingdeeExchange::numericVoucherNumber)
                .filter(number -> number != null)
                .max(Integer::compareTo)
                .map(number -> number + 1)
                .orElse(1000);
        List<VoucherResponses.Voucher> merged = new ArrayList<>();
        for (Map.Entry<MergeGroupKey, List<VoucherResponses.Voucher>> entry : groups.entrySet()) {
            if (entry.getValue().size() < 2) {
                unchanged.addAll(entry.getValue());
                continue;
            }
            merged.add(mergeGroup(entry.getValue(), nextVoucherNumber++));
        }
        unchanged.addAll(merged);
        return unchanged;
    }

    private VoucherResponses.Voucher mergeGroup(
            List<VoucherResponses.Voucher> group, int voucherNumber) {
        VoucherResponses.Voucher base = group.stream()
                .max(Comparator.comparing(VoucherResponses.Voucher::voucherDate))
                .orElseThrow();
        Map<ExportLineKey, MergedLine> aggregate = new LinkedHashMap<>();
        for (VoucherResponses.Voucher voucher : group) {
            String sourceSummary = voucher.summary() == null ? "" : voucher.summary();
            for (VoucherResponses.Line line : voucher.lines()) {
                ExportLineKey key = new ExportLineKey(line.accountId(), line.side(), line.currency(),
                        line.exchangeRate().stripTrailingZeros());
                aggregate.computeIfAbsent(key, ignored -> new MergedLine(line, sourceSummary)).add(line);
            }
        }
        List<MergedLine> ordered = aggregate.values().stream()
                .sorted(Comparator.comparingInt(line -> "DEBIT".equals(line.first.side()) ? 0 : 1))
                .toList();
        List<VoucherResponses.Line> lines = new ArrayList<>();
        int lineNumber = 1;
        for (MergedLine line : ordered) {
            lines.add(line.toLine(lineNumber++));
        }
        return new VoucherResponses.Voucher(base.id(), base.ledgerId(), base.periodId(), base.voucherDate(),
                base.voucherType(), Integer.toString(voucherNumber),
                base.summary(),
                base.status(), base.approvalRequired(), base.version(), lines);
    }

    private MergeCategory category(
            VoucherResponses.Voucher voucher,
            Map<UUID, LedgerResponses.Account> accounts) {
        if (matchesRule(voucher, accounts, "CREDIT", "应收账款", "DEBIT")) {
            return MergeCategory.MAIN_COLLECTION;
        }
        if (matchesRule(voucher, accounts, "DEBIT", "其他应付款", "CREDIT")) {
            return MergeCategory.DAILY_PAYMENT;
        }
        if (matchesRule(voucher, accounts, "DEBIT", "应付账款", "CREDIT")) {
            return MergeCategory.MAIN_PAYMENT;
        }
        return matchesRule(voucher, accounts, "DEBIT", "财务费用", "CREDIT")
                ? MergeCategory.BANK_FEE : null;
    }

    private boolean matchesRule(
            VoucherResponses.Voucher voucher,
            Map<UUID, LedgerResponses.Account> accounts,
            String businessSide, String businessAccountName, String bankSide) {
        return hasLine(voucher, accounts, businessSide, businessAccountName)
                && hasLine(voucher, accounts, bankSide, "银行存款");
    }

    private boolean hasLine(
            VoucherResponses.Voucher voucher,
            Map<UUID, LedgerResponses.Account> accounts,
            String side, String topLevelName) {
        return !matchingAccounts(voucher, accounts, side, topLevelName).isEmpty();
    }

    private List<LedgerResponses.Account> matchingAccounts(
            VoucherResponses.Voucher voucher,
            Map<UUID, LedgerResponses.Account> accounts,
            String side, String topLevelName) {
        return voucher.lines().stream()
                .filter(line -> side.equals(line.side()))
                .map(line -> accounts.get(line.accountId()))
                .filter(account -> account != null)
                .filter(account -> topLevelAccount(account, accounts).name().equals(topLevelName))
                .toList();
    }

    private Set<UUID> bankAccountIds(
            VoucherResponses.Voucher voucher,
            Map<UUID, LedgerResponses.Account> accounts) {
        Set<UUID> bankAccounts = new LinkedHashSet<>();
        for (VoucherResponses.Line line : voucher.lines()) {
            LedgerResponses.Account account = accounts.get(line.accountId());
            if (account != null && topLevelAccount(account, accounts).name().equals("银行存款")) {
                bankAccounts.add(line.accountId());
            }
        }
        return Set.copyOf(bankAccounts);
    }

    private LedgerResponses.Account topLevelAccount(
            LedgerResponses.Account account,
            Map<UUID, LedgerResponses.Account> accounts) {
        Set<UUID> visited = new LinkedHashSet<>();
        LedgerResponses.Account current = account;
        while (current.parentId() != null && visited.add(current.id())) {
            LedgerResponses.Account parent = accounts.get(current.parentId());
            if (parent == null) {
                break;
            }
            current = parent;
        }
        return current;
    }

    private static Integer numericVoucherNumber(String value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.valueOf(value.trim());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private enum MergeCategory {
        MAIN_COLLECTION,
        DAILY_PAYMENT,
        MAIN_PAYMENT,
        BANK_FEE
    }

    private record MergeGroupKey(MergeCategory category, Set<UUID> bankAccountIds) {
    }

    private record ExportLineKey(
            UUID accountId, String side, String currency, BigDecimal exchangeRate) {
    }

    private static final class MergedLine {
        private final VoucherResponses.Line first;
        private final String sourceSummary;
        private BigDecimal originalAmount = BigDecimal.ZERO;
        private BigDecimal baseAmount = BigDecimal.ZERO;

        private MergedLine(VoucherResponses.Line first, String sourceSummary) {
            this.first = first;
            this.sourceSummary = sourceSummary;
        }

        private void add(VoucherResponses.Line line) {
            originalAmount = originalAmount.add(line.originalAmount());
            baseAmount = baseAmount.add(line.baseAmount());
        }

        private VoucherResponses.Line toLine(int lineNumber) {
            return new VoucherResponses.Line(first.id(), lineNumber, first.accountId(), first.side(),
                    first.currency(), originalAmount, first.exchangeRate(), baseAmount,
                    sourceSummary);
        }
    }

    private ParsedWorkbook parse(UUID ledgerId, InputStream input) {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheet("AccountEntries");
            if (sheet == null) {
                for (Sheet candidate : workbook) {
                    if (matchesHeader(candidate.getRow(2), NATIVE_HEADERS)) {
                        return parseNative(ledgerId, candidate);
                    }
                }
                throw invalid("Missing AccountEntries or native voucher-list worksheet");
            }
            validateHeader(sheet.getRow(0));
            Map<VoucherKey, List<ImportedLine>> grouped = new LinkedHashMap<>();
            int rowCount = 0;
            for (int index = 1; index <= sheet.getLastRowNum(); index++) {
                Row row = sheet.getRow(index);
                if (row == null || blank(row)) {
                    continue;
                }
                ImportedLine line = parseLine(ledgerId, row, index + 1);
                grouped.computeIfAbsent(line.key(), ignored -> new ArrayList<>()).add(line);
                rowCount++;
            }
            if (rowCount == 0) {
                throw invalid("The workbook contains no entry rows");
            }
            List<VoucherRequests.Create> requests = grouped.entrySet().stream()
                    .map(entry -> request(ledgerId, entry.getKey(), entry.getValue())).toList();
            return new ParsedWorkbook(requests, rowCount);
        } catch (ApiProblemException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalid("The workbook is not a readable Kingdee Excel file");
        }
    }

    private ParsedWorkbook parseNative(UUID ledgerId, Sheet sheet) {
        Map<VoucherKey, List<ImportedLine>> grouped = new LinkedHashMap<>();
        VoucherKey current = null;
        int rowCount = 0;
        for (int index = 3; index <= sheet.getLastRowNum(); index++) {
            Row row = sheet.getRow(index);
            if (row == null || blank(row, NATIVE_HEADERS.size())) {
                continue;
            }
            String date = nativeText(row, 0, index + 1);
            String label = nativeText(row, 1, index + 1);
            if (!date.isBlank() || !label.isBlank()) {
                if (date.isBlank() || label.isBlank()) {
                    throw nativeRowProblem(index + 1, "日期/凭证字号", "must both be present");
                }
                int separator = label.lastIndexOf('-');
                if (separator < 1 || separator == label.length() - 1) {
                    throw nativeRowProblem(index + 1, "凭证字号", "must use type-number");
                }
                current = new VoucherKey(date(row, index + 1), label.substring(0, separator).trim(),
                        label.substring(separator + 1).trim());
            }
            if (current == null) {
                throw nativeRowProblem(index + 1, "日期/凭证字号", "is missing");
            }
            ImportedLine line = parseNativeLine(ledgerId, current, row, index + 1, rowCount + 1);
            grouped.computeIfAbsent(current, ignored -> new ArrayList<>()).add(line);
            rowCount++;
        }
        if (rowCount == 0) {
            throw invalid("The workbook contains no voucher rows");
        }
        List<VoucherRequests.Create> requests = grouped.entrySet().stream()
                .map(entry -> request(ledgerId, entry.getKey(), entry.getValue())).toList();
        return new ParsedWorkbook(requests, rowCount);
    }

    private ImportedLine parseNativeLine(
            UUID ledgerId, VoucherKey key, Row row, int rowNumber, int lineNumber) {
        BigDecimal debit = nativeDecimal(row, 4, rowNumber);
        BigDecimal credit = nativeDecimal(row, 5, rowNumber);
        boolean hasDebit = debit != null && debit.signum() != 0;
        boolean hasCredit = credit != null && credit.signum() != 0;
        if (hasDebit == hasCredit) {
            throw nativeRowProblem(rowNumber, "借方金额/贷方金额", "exactly one amount must be non-zero");
        }
        BigDecimal amount = hasDebit ? debit : credit;
        String side = hasDebit ? "DEBIT" : "CREDIT";
        String account = nativeText(row, 3, rowNumber);
        String code = account.split("\\s+", 2)[0];
        if (account.isBlank() || !code.matches("\\d+")) {
            throw nativeRowProblem(rowNumber, "科目", "must start with a numeric account code");
        }
        return new ImportedLine(key, lineNumber, ledgers.accountId(ledgerId, code), side, "CNY",
                amount, BigDecimal.ONE, nativeText(row, 2, rowNumber), amount);
    }

    private BigDecimal nativeDecimal(Row row, int column, int rowNumber) {
        String value = nativeText(row, column, rowNumber).replace(",", "");
        if (value.isBlank()) {
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw nativeRowProblem(rowNumber, NATIVE_HEADERS.get(column), "must be numeric");
        }
    }

    private String nativeText(Row row, int column, int rowNumber) {
        if (cell(row, column).getCellType() == CellType.FORMULA) {
            throw nativeRowProblem(rowNumber, NATIVE_HEADERS.get(column), "must not contain a formula");
        }
        return text(row, column);
    }

    private ImportedLine parseLine(UUID ledgerId, Row row, int rowNumber) {
        BigDecimal attachments = decimal(row, 3, false, rowNumber);
        if (attachments != null && attachments.signum() != 0) {
            throw unsupported(rowNumber, "附件数");
        }
        for (int column = 10; column <= 20; column++) {
            if (!text(row, column).isBlank()) {
                throw unsupported(rowNumber, HEADERS.get(column));
            }
        }
        LocalDate date = date(row, rowNumber);
        String type = required(row, 1, rowNumber);
        String number = required(row, 2, rowNumber);
        int lineNumber;
        try {
            lineNumber = decimal(row, 4, true, rowNumber).intValueExact();
        } catch (ArithmeticException exception) {
            throw rowProblem(rowNumber, "分录序号", "must be an integer");
        }
        if (lineNumber < 1) {
            throw rowProblem(rowNumber, "分录序号", "must be positive");
        }
        BigDecimal debit = decimal(row, 8, false, rowNumber);
        BigDecimal credit = decimal(row, 9, false, rowNumber);
        if ((nonZero(debit) ? 1 : 0) + (nonZero(credit) ? 1 : 0) != 1) {
            throw rowProblem(rowNumber, "借方金额/贷方金额", "exactly one amount must be non-zero");
        }
        String side = nonZero(debit) ? "DEBIT" : "CREDIT";
        BigDecimal baseAmount = nonZero(debit) ? debit : credit;
        BigDecimal rate = decimal(row, 23, false, rowNumber);
        rate = rate == null ? BigDecimal.ONE : rate;
        BigDecimal original = decimal(row, 21, false, rowNumber);
        original = original == null
                ? baseAmount.divide(rate, 4, RoundingMode.HALF_UP) : original;
        if (!nonZero(original) || !positive(rate)
                || original.multiply(rate).setScale(2, RoundingMode.HALF_UP)
                .compareTo(baseAmount.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw rowProblem(rowNumber, "原币金额/汇率", "does not match the debit or credit amount");
        }
        String currency = required(row, 22, rowNumber).toUpperCase(Locale.ROOT);
        currency = "RMB".equals(currency) ? "CNY" : currency;
        if (!currency.matches("[A-Z]{3}")) {
            throw rowProblem(rowNumber, "币别", "must be a three-letter currency code");
        }
        return new ImportedLine(new VoucherKey(date, type, number), lineNumber,
                ledgers.accountId(ledgerId, required(row, 6, rowNumber)), side, currency,
                original, rate, text(row, 5), baseAmount);
    }

    private VoucherRequests.Create request(UUID ledgerId, VoucherKey key, List<ImportedLine> imported) {
        imported.sort(Comparator.comparingInt(ImportedLine::lineNumber));
        if (imported.stream().map(ImportedLine::lineNumber).distinct().count() != imported.size()) {
            throw invalid("Voucher " + key.number() + " contains duplicate entry numbers");
        }
        BigDecimal debit = total(imported, "DEBIT");
        BigDecimal credit = total(imported, "CREDIT");
        if (debit.setScale(2, RoundingMode.HALF_UP).compareTo(
                credit.setScale(2, RoundingMode.HALF_UP)) != 0) {
            throw invalid("Voucher " + key.number() + " is not balanced");
        }
        List<VoucherRequests.Line> lines = imported.stream().map(line -> new VoucherRequests.Line(
                line.accountId(), line.side(), line.currency(), line.originalAmount(),
                line.exchangeRate(), line.summary())).toList();
        return new VoucherRequests.Create(ledgers.periodId(ledgerId, YearMonth.from(key.date()).toString()),
                key.date(), key.type(), key.number(), imported.getFirst().summary(), lines);
    }

    private BigDecimal total(List<ImportedLine> lines, String side) {
        return lines.stream().filter(line -> side.equals(line.side())).map(ImportedLine::baseAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private void validateHeader(Row row) {
        if (row == null) {
            throw invalid("Missing header row");
        }
        for (int index = 0; index < HEADERS.size(); index++) {
            if (!HEADERS.get(index).equals(text(row, index))) {
                throw invalid("Column " + (index + 1) + " must be " + HEADERS.get(index));
            }
        }
    }

    private boolean matchesHeader(Row row, List<String> headers) {
        if (row == null) {
            return false;
        }
        for (int index = 0; index < headers.size(); index++) {
            if (!headers.get(index).equals(text(row, index))) {
                return false;
            }
        }
        return true;
    }

    private void write(Row row, VoucherResponses.Voucher voucher, VoucherResponses.Line line,
                       LedgerResponses.Account account) {
        for (int index = 0; index < HEADERS.size(); index++) {
            cell(row, index).setBlank();
        }
        cell(row, 0).setCellValue(voucher.voucherDate().toString());
        cell(row, 1).setCellValue(voucher.voucherType());
        cell(row, 2).setCellValue(voucher.voucherNumber());
        cell(row, 3).setCellValue(0);
        cell(row, 4).setCellValue(line.lineNo());
        cell(row, 5).setCellValue(line.summary() == null ? voucher.summary() : line.summary());
        cell(row, 6).setCellValue(account.code());
        cell(row, 7).setCellValue(account.name());
        cell(row, "DEBIT".equals(line.side()) ? 8 : 9).setCellValue(line.baseAmount().doubleValue());
        cell(row, 21).setCellValue(line.originalAmount().doubleValue());
        cell(row, 22).setCellValue("CNY".equals(line.currency()) ? "RMB" : line.currency());
        cell(row, 23).setCellValue(line.exchangeRate().doubleValue());
    }

    private CellStyle[] cellStyles(Row source) {
        CellStyle[] styles = new CellStyle[HEADERS.size()];
        for (int index = 0; index < HEADERS.size(); index++) {
            styles[index] = cell(source, index).getCellStyle();
        }
        return styles;
    }

    private void copyStyle(CellStyle[] styles, short height, Row target) {
        target.setHeight(height);
        for (int index = 0; index < styles.length; index++) {
            cell(target, index).setCellStyle(styles[index]);
        }
    }

    private LedgerResponses.Account account(Map<UUID, LedgerResponses.Account> accounts, UUID accountId) {
        LedgerResponses.Account account = accounts.get(accountId);
        if (account == null) {
            throw problem(422, "KINGDEE_ACCOUNT_NOT_FOUND", "Kingdee export failed",
                    "A voucher line references an unavailable account");
        }
        return account;
    }

    private LocalDate date(Row row, int rowNumber) {
        Cell cell = cell(row, 0);
        try {
            if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            return LocalDate.parse(text(row, 0));
        } catch (Exception exception) {
            throw rowProblem(rowNumber, "日期", "must use yyyy-MM-dd");
        }
    }

    private BigDecimal decimal(Row row, int column, boolean required, int rowNumber) {
        String value = text(row, column).replace(",", "");
        if (value.isBlank()) {
            if (required) {
                throw rowProblem(rowNumber, HEADERS.get(column), "is required");
            }
            return null;
        }
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException exception) {
            throw rowProblem(rowNumber, HEADERS.get(column), "must be numeric");
        }
    }

    private String required(Row row, int column, int rowNumber) {
        String value = text(row, column);
        if (value.isBlank()) {
            throw rowProblem(rowNumber, HEADERS.get(column), "is required");
        }
        return value;
    }

    private String text(Row row, int column) {
        // ponytail: one formatter lock is enough for file-transfer traffic; use one formatter per import if concurrency matters.
        synchronized (formatter) {
            return formatter.formatCellValue(cell(row, column)).trim();
        }
    }

    private boolean blank(Row row) {
        return blank(row, HEADERS.size());
    }

    private boolean blank(Row row, int columns) {
        for (int index = 0; index < columns; index++) {
            if (!text(row, index).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private Cell cell(Row row, int column) {
        return row.getCell(column, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private boolean nonZero(BigDecimal value) {
        return value != null && value.signum() != 0;
    }

    private ApiProblemException unsupported(int row, String field) {
        return problem(422, "KINGDEE_FIELD_UNSUPPORTED", "Unsupported Kingdee field",
                "Row " + row + " field " + field + " cannot be preserved by the current voucher model");
    }

    private ApiProblemException rowProblem(int row, String field, String detail) {
        return problem(422, "KINGDEE_ROW_INVALID", "Invalid Kingdee row",
                "Row " + row + " field " + field + " " + detail);
    }

    private ApiProblemException nativeRowProblem(int row, String field, String detail) {
        return problem(422, "KINGDEE_ROW_INVALID", "Invalid Kingdee row",
                "Row " + row + " field " + field + " " + detail);
    }

    private ApiProblemException invalid(String detail) {
        return problem(422, "KINGDEE_WORKBOOK_INVALID", "Invalid Kingdee workbook", detail);
    }

    private ApiProblemException problem(int status, String code, String title, String detail) {
        return new ApiProblemException(status, code, title, detail, false);
    }

    public record ImportResult(int voucherCount, int rowCount) {
    }

    private record ParsedWorkbook(List<VoucherRequests.Create> vouchers, int rowCount) {
    }

    private record VoucherKey(LocalDate date, String type, String number) {
    }

    private record ImportedLine(VoucherKey key, int lineNumber, UUID accountId, String side, String currency,
                                BigDecimal originalAmount, BigDecimal exchangeRate, String summary,
                                BigDecimal baseAmount) {
    }
}
