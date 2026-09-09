import test from 'node:test';
import assert from 'node:assert/strict';
import { cents, identifier, dateOnly, transaction, validateStatement, deduplicateTransactions, matchTransfers, classify, reconcileVouchers } from './source-core.mjs';
import { parseBankSheet } from './bank-adapters.mjs';
import { parseInvoiceSheet } from './process-sources.mjs';

const make = (account = 'A', side = 'OUT', amount = 10000, ref = 'r1', extra = {}) => transaction(
  { account, bank: 'Test', currency: 'CNY' },
  { date: '2026-08-01', [side === 'IN' ? 'inCents' : 'outCents']: amount, reference: ref, ...extra },
  { file: 'synthetic.xlsx', sheet: 'sheet', row: 2 },
);
const pair = () => [make('A', 'OUT', 10000, 'a', { counterAccount: 'B' }), make('B', 'IN', 10000, 'b', { counterAccount: 'A' })];
const line = (accountId, side, originalAmount) => ({ accountId, side, originalAmount, currency: 'CNY' });

test('money is exact, signed, and rejects lost precision', () => {
  assert.equal(cents('1,234.5600'), 123456);
  assert.equal(cents('-0.01'), -1);
  assert.equal(cents(null), 0);
  for (const value of ['1.001', 'NaN', '1e6', Infinity, '9007199254740992']) assert.throws(() => cents(value));
  assert.throws(() => identifier(12345678901234567890));
  assert.equal(identifier('001234'), '001234');
});
test('dates must represent real calendar dates', () => {
  assert.equal(dateOnly('20260806120708'), '2026-08-06');
  assert.equal(dateOnly('2026-08-06 12:07:08'), '2026-08-06');
  assert.throws(() => dateOnly('20260230'));
});
test('ambiguous directions fail and reversals retain their cash direction', () => {
  assert.throws(() => make('A', 'IN', 100, 'x', { outCents: 20 }));
  assert.throws(() => make('A', 'IN', 0));
  assert.equal(make('A', 'IN', -100).direction, 'OUT');
});
test('statement balances and declared totals independently catch differences', () => {
  const transactions = [make('A', 'OUT', 100, 'x', { balanceCents: 900 })];
  assert.deepEqual(validateStatement({ transactions, openingCents: 1000, closingCents: 900, declared: { count: 1, expenseCents: 100 } }).issues, []);
  const result = validateStatement({ transactions, openingCents: 1100, closingCents: 800, declared: { count: 2 } });
  assert.deepEqual(result.issues.map(x => x.type), ['running_balance_mismatch', 'statement_total_mismatch', 'closing_balance_mismatch']);
});
test('overlapping references deduplicate, conflicts stay visible', () => {
  const original = make(), copy = make(); copy.sources[0].file = 'copy.xlsx';
  const result = deduplicateTransactions([original, copy]);
  assert.equal(result.transactions.length, 1);
  assert.equal(result.transactions[0].sources.length, 2);
  assert.equal(result.issues[0].type, 'duplicate_transaction');
  assert.equal(deduplicateTransactions([make(), make('A', 'OUT', 10001)]).issues[0].type, 'conflicting_transaction_reference');
  assert.equal(deduplicateTransactions([make('A', 'OUT', 1, ''), make('A', 'OUT', 1, '')]).transactions.length, 2);
});
test('reciprocal bank transfer has one pair for two source rows', () => {
  const result = matchTransfers(pair());
  assert.equal(result.pairs.length, 1);
  assert.equal(result.pairs[0].amountCents, 10000);
  assert.deepEqual(result.issues, []);
});
test('same-amount duplicate candidates are never greedily paired', () => {
  const rows = pair(); rows.push(make('B', 'IN', 10000, 'b2', { counterAccount: 'A' }));
  const result = matchTransfers(rows);
  assert.equal(result.pairs.length, 0);
  assert(result.issues.every(x => x.type === 'ambiguous_transfer'));
});
test('transfers require reciprocal accounts, date and currency', () => {
  for (const extra of [{ currency: 'USD' }, { date: '2026-08-02' }, { counterAccount: 'EXTERNAL' }]) {
    const rows = [pair()[0], make('B', 'IN', 10000, 'b', { counterAccount: 'A', ...extra })];
    assert.equal(matchTransfers(rows).pairs.length, 0);
  }
  assert.equal(matchTransfers([make('A', 'OUT', 10000, 'a', { counterparty: '本公司' }), make('B', 'IN', 10000, 'b', { counterparty: '本公司' })]).pairs.length, 0);
});
test('classification requires verified ledger rules and surfaces conflicts', () => {
  const rows = [make('A', 'OUT', 100, 'x', { memo: '银行手续费' })];
  assert.equal(classify(rows)[0].status, 'needs_review');
  const rule = { match: { kind: 'bank_fee' }, accountCode: '6603' };
  assert.throws(() => classify(rows, [], [rule]));
  assert.throws(() => classify(rows, [], [rule], [{ code: '6603', status: 'INACTIVE' }]));
  assert.throws(() => classify(rows, [], [rule], [{ code: '6603', isLeaf: false }]));
  assert.equal(classify(rows, [], [rule], [{ code: '6603', status: 'ACTIVE', isLeaf: true }])[0].accountCode, '6603');
  const conflict = { match: { direction: 'OUT' }, accountCode: '6602' };
  assert.equal(classify(rows, [], [rule, conflict], ['6603', '6602'].map(code => ({ code, status: 'ACTIVE', isLeaf: true })))[0].status, 'conflicting_rules');
  assert.throws(() => classify(rows, [], [{ ...rule, match: { memoIncludes: '' } }], []));
  assert(classify(pair(), matchTransfers(pair()).pairs).every(x => x.status === 'paired' && x.accountCode === null));
});
test('voucher verification detects duplicate posting, unbalanced entries and missing bank legs', () => {
  const rows = pair(), accounts = { a: 'A', b: 'B' };
  const voucher = { id: 'v1', lines: [line('b', 'DEBIT', '100'), line('a', 'CREDIT', '100')] };
  assert.equal(reconcileVouchers(rows, [voucher], accounts).ok, true);
  assert.equal(reconcileVouchers(rows, [voucher, voucher], accounts).ok, false);
  assert.equal(reconcileVouchers(rows, [voucher, { ...voucher, id: 'v2' }], accounts).ok, false);
  assert.equal(reconcileVouchers(rows, [{ id: 'bad', lines: [line('a', 'CREDIT', '100')] }], accounts).issues[0].type, 'unbalanced_voucher');
  assert.equal(reconcileVouchers(rows, [{ id: 'wrong', lines: [line('other', 'DEBIT', '100'), line('a', 'CREDIT', '100')] }], accounts).ok, false);
});
const paHeader = ['交易时间', '账号', '币种', '收入', '支出', '冲正标志', '账户余额', '对方账号', '对方户名', '摘要', '交易用途', '核心唯一流水号', '单位结算卡卡号'];
const paRow = (incoming, outgoing, balance, ref = 'r1', card = '23') => ['20260806120708', '00123', 'RMB', incoming, outgoing, '_', balance, 'EXT', '对方', '网银', '', ref, card];
test('Ping An export blank marker is distinguished from an actual 23 yuan transaction', () => {
  const parsed = parseBankSheet([paHeader, paRow('23', '100.00', '900.00'), paRow('23', '23.00', '877.00', 'r2')], 'sheet', 'pa.xlsx');
  assert.equal(parsed.transactions.length, 2);
  assert.equal(parsed.transactions[1].amountCents, 2300);
  assert.equal(parsed.transactions[1].direction, 'OUT');
  assert.deepEqual(parsed.validation.issues, []);
  assert.throws(() => parseBankSheet([paHeader, paRow('23', '23', '900')], 'sheet', 'pa.xlsx'));
  assert.throws(() => parseBankSheet([paHeader, paRow(23, '100', '900', 'r1', '')], 'sheet', 'pa.xlsx'));
  assert.throws(() => parseBankSheet([paHeader, ['broken date', '00123', 'RMB', '100']], 'sheet', 'pa.xlsx'));
});
test('unsupported formats require an adapter instead of guessing column positions', () => {
  assert.throws(() => parseBankSheet([['日期', '金额'], ['2026-08-01', 100]], 'sheet', 'unknown.xlsx'));
});
test('invoice lines preserve repeated invoice numbers and signed red records', () => {
  const header = ['销方名称', '购买方名称', '金额', '税额', '价税合计', '开票日期', '发票号码', '发票状态', '是否正数发票'];
  const result = parseInvoiceSheet([header, ['销方', '购方', 100, 13, 113, '2026-08-01', '001', '正常', '是'], ['销方', '购方', 200, 26, 226, '2026-08-01', '001', '正常', '是'], ['销方', '购方', -100, -13, -113, '2026-08-01', '002', '正常', '否']], 'sheet', 'invoices.xlsx');
  assert.equal(result.records.length, 3);
  assert.equal(result.records[2].grossCents, -11300);
  assert.equal(result.issues[0].type, 'invoice_status_review');
  const wrong = parseInvoiceSheet([header, ['销方', '购方', 100, 13, 112, '2026-08-01', '003', '正常', '是']], 'sheet', 'bad.xlsx');
  assert.equal(wrong.issues[0].type, 'invoice_amount_mismatch');
});
