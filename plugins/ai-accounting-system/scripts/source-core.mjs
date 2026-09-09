import { createHash } from 'node:crypto';

export function cents(value) {
  if (value == null || String(value).trim() === '') return 0;
  const text = String(value).trim().replaceAll(',', '');
  const match = /^(-?)(\d+)(?:\.(\d+))?$/.exec(text);
  if (!match || (match[3] || '').slice(2).replaceAll('0', '')) throw new Error(`Invalid money: ${value}`);
  const result = Number(match[2]) * 100 + Number(((match[3] || '') + '00').slice(0, 2));
  if (!Number.isSafeInteger(result)) throw new Error(`Money exceeds safe integer cents: ${value}`);
  return match[1] ? -result : result;
}

export function identifier(value) {
  if (typeof value === 'number' && !Number.isSafeInteger(value)) throw new Error('Unsafe numeric account or reference');
  return String(value ?? '').trim();
}

export function dateOnly(value) {
  const text = String(value ?? '').trim();
  const match = /^(\d{4})-?(\d{2})-?(\d{2})(?:\D|\d|$)/.exec(text);
  if (!match) throw new Error(`Invalid date: ${value}`);
  const date = `${match[1]}-${match[2]}-${match[3]}`;
  if (new Date(`${date}T00:00:00Z`).toISOString().slice(0, 10) !== date) throw new Error(`Invalid date: ${value}`);
  return date;
}

export const digest = value => createHash('sha256').update(value).digest('hex');
export const normalizeHeader = value => String(value ?? '').replace(/[\s　]/g, '');

export function transaction(statement, raw, source) {
  const incoming = raw.inCents || 0, outgoing = raw.outCents || 0;
  if ((incoming !== 0 && outgoing !== 0) || (incoming === 0 && outgoing === 0)) throw new Error(`Ambiguous direction at row ${source.row}`);
  const signed = incoming - outgoing;
  const record = {
    account: statement.account, bank: statement.bank, currency: raw.currency || statement.currency,
    date: dateOnly(raw.date), direction: signed > 0 ? 'IN' : 'OUT', amountCents: Math.abs(signed),
    counterAccount: identifier(raw.counterAccount), counterparty: String(raw.counterparty ?? '').trim(),
    memo: String(raw.memo ?? '').trim(), reference: identifier(raw.reference), sources: [source],
  };
  if (raw.balanceCents != null) record.balanceCents = raw.balanceCents;
  record.id = digest(JSON.stringify([record.account, record.currency, record.date, record.direction, record.amountCents, record.reference || source]));
  return record;
}

export function validateStatement(statement) {
  const issues = [], rows = statement.transactions;
  let incomeCents = 0, expenseCents = 0, balance = statement.openingCents;
  for (const row of rows) {
    const signed = row.direction === 'IN' ? row.amountCents : -row.amountCents;
    if (signed > 0) incomeCents += signed; else expenseCents -= signed;
    if (balance != null && row.balanceCents != null && balance + signed !== row.balanceCents) {
      issues.push({ type: 'running_balance_mismatch', row: row.sources[0].row, differenceCents: row.balanceCents - balance - signed });
    }
    if (row.balanceCents != null) balance = row.balanceCents;
    else if (balance != null) balance += signed;
  }
  for (const [key, actual] of [['incomeCents', incomeCents], ['expenseCents', expenseCents], ['count', rows.length]]) {
    if (statement.declared?.[key] != null && statement.declared[key] !== actual) issues.push({ type: 'statement_total_mismatch', key, expected: statement.declared[key], actual });
  }
  if (statement.closingCents != null && balance != null && statement.closingCents !== balance) issues.push({ type: 'closing_balance_mismatch', expected: statement.closingCents, actual: balance });
  return { incomeCents, expenseCents, count: rows.length, issues };
}

export function deduplicateTransactions(rows) {
  const unique = [], byReference = new Map(), issues = [];
  for (const row of rows) {
    if (!row.reference) { unique.push(row); continue; }
    const key = JSON.stringify([row.account, row.currency, row.reference]);
    const prior = byReference.get(key);
    if (!prior) { byReference.set(key, row); unique.push(row); continue; }
    if (prior.id !== row.id || prior.counterAccount !== row.counterAccount || (prior.balanceCents != null && row.balanceCents != null && prior.balanceCents !== row.balanceCents)) {
      issues.push({ type: 'conflicting_transaction_reference', reference: row.reference, sources: [...prior.sources, ...row.sources] });
      unique.push(row);
    } else { prior.sources.push(...row.sources); issues.push({ type: 'duplicate_transaction', transactionId: row.id }); }
  }
  return { transactions: unique, issues };
}

export function matchTransfers(rows) {
  const pairs = [], issues = [], candidates = new Map();
  const known = new Set(rows.map(row => row.account));
  for (const row of rows) {
    if (row.counterAccount === row.account || !known.has(row.counterAccount)) continue;
    candidates.set(row.id, rows.filter(other => other.id !== row.id && other.account === row.counterAccount && other.counterAccount === row.account && other.currency === row.currency && other.date === row.date && other.direction !== row.direction && other.amountCents === row.amountCents));
  }
  const paired = new Set();
  for (const row of rows) {
    if (!candidates.has(row.id) || paired.has(row.id)) continue;
    const possible = candidates.get(row.id);
    if (possible.length !== 1 || candidates.get(possible[0].id)?.length !== 1) {
      issues.push({ type: possible.length ? 'ambiguous_transfer' : 'unmatched_transfer', transactionId: row.id, candidateIds: possible.map(t => t.id) });
      continue;
    }
    const other = possible[0], outgoing = row.direction === 'OUT' ? row : other, incoming = row.direction === 'IN' ? row : other;
    paired.add(row.id); paired.add(other.id);
    pairs.push({ id: digest([outgoing.id, incoming.id].join(':')), outgoingId: outgoing.id, incomingId: incoming.id, from: outgoing.account, to: incoming.account, date: outgoing.date, currency: outgoing.currency, amountCents: outgoing.amountCents });
  }
  return { pairs, issues };
}

function hint(row) {
  const text = `${row.counterparty} ${row.memo}`;
  if (/手续费|服务费.*银行|网上企业银行|收费项目/.test(text)) return 'bank_fee';
  if (/贷款|借款|还本|利息/.test(text)) return 'loan';
  if (/社保/.test(text)) return 'social_insurance';
  if (/公积金/.test(text)) return 'housing_fund';
  if (/工资/.test(text)) return 'payroll';
  if (/税费|税款/.test(text)) return 'tax';
  if (/保证金|押金|质保金/.test(text)) return 'deposit';
  if (/退回|退款/.test(text)) return 'refund';
  if (/报销/.test(text)) return 'reimbursement';
  return 'unknown';
}

export function classify(rows, pairs = [], rules = [], accounts = []) {
  const transferIds = new Set(pairs.flatMap(p => [p.outgoingId, p.incomingId]));
  const allowedKeys = new Set(['bank', 'direction', 'counterparty', 'counterAccount', 'memoIncludes', 'kind']);
  for (const rule of rules) {
    if (!rule.match || !Object.keys(rule.match).length || Object.entries(rule.match).some(([key, value]) => !allowedKeys.has(key) || typeof value !== 'string' || !value.trim())) throw new Error('Invalid or empty classification rule');
    if (!rule.accountCode || !accounts.some(a => a.code === rule.accountCode && a.isLeaf === true && a.status === 'ACTIVE')) throw new Error(`Rule references an unverified account: ${rule.accountCode}`);
  }
  return rows.map(row => {
    if (transferIds.has(row.id)) return { transactionId: row.id, kind: 'internal_transfer', status: 'paired', accountCode: null };
    const kind = hint(row);
    const matched = rules.filter(rule => Object.entries(rule.match).every(([key, value]) => key === 'memoIncludes' ? row.memo.includes(value) : key === 'kind' ? kind === value : row[key] === value));
    const codes = [...new Set(matched.map(r => r.accountCode))];
    return { transactionId: row.id, kind, status: codes.length === 1 ? 'rule_matched' : codes.length > 1 ? 'conflicting_rules' : 'needs_review', accountCode: codes.length === 1 ? codes[0] : null };
  });
}

export function reconcileVouchers(rows, vouchers, bankAccounts) {
  const expected = new Map(), actual = new Map(), issues = [];
  function add(map, account, currency, direction, value) {
    const key = JSON.stringify([account, currency]);
    const totals = map.get(key) || { account, currency, IN: 0, OUT: 0 };
    totals[direction] += value; map.set(key, totals);
  }
  for (const row of rows) add(expected, row.account, row.currency, row.direction, row.amountCents);
  const seen = new Set();
  for (const voucher of vouchers) {
    if (voucher.id && seen.has(voucher.id)) { issues.push({ type: 'duplicate_voucher', voucherId: voucher.id }); continue; }
    if (voucher.id) seen.add(voucher.id);
    let balance = 0;
    for (const line of voucher.lines) {
      if (!['DEBIT', 'CREDIT'].includes(line.side)) throw new Error('Unknown voucher line side');
      const value = cents(line.originalAmount);
      const base = line.baseAmount != null ? cents(line.baseAmount) : Number(line.exchangeRate ?? 1) === 1 ? value : null;
      if (base == null) throw new Error('Foreign-currency voucher requires baseAmount for verification');
      balance += (line.side === 'DEBIT' ? 1 : -1) * base;
      const account = bankAccounts[line.accountId] || bankAccounts[line.accountCode];
      if (account) add(actual, account, line.currency, line.side === 'DEBIT' ? 'IN' : 'OUT', value);
    }
    if (balance !== 0) issues.push({ type: 'unbalanced_voucher', voucherId: voucher.id, differenceCents: balance });
  }
  const checks = [...new Set([...expected.keys(), ...actual.keys()])].map(key => {
    const e = expected.get(key) || { IN: 0, OUT: 0 }, a = actual.get(key) || { IN: 0, OUT: 0 };
    return { account: JSON.parse(key)[0], currency: JSON.parse(key)[1], expectedIncomeCents: e.IN, expectedExpenseCents: e.OUT, incomeDifferenceCents: a.IN - e.IN, expenseDifferenceCents: a.OUT - e.OUT };
  });
  return { ok: issues.length === 0 && checks.every(c => c.incomeDifferenceCents === 0 && c.expenseDifferenceCents === 0), checks, issues };
}
