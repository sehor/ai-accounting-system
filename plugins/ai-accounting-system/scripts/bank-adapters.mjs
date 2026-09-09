import { cents, identifier, normalizeHeader as h, transaction, validateStatement } from './source-core.mjs';

const yuan = value => /^(RMB|CNY|人民币|人民币元)$/.test(String(value ?? '').trim()) ? 'CNY' : String(value ?? '').trim();
const label = (rows, text) => rows.find(row => h(row[0]).startsWith(text))?.[1];
const headerAt = (rows, required) => rows.findIndex(row => required.every(name => row.some(cell => h(cell) === name)));
const field = (header, text) => {
  const index = header.findIndex(cell => h(cell) === text);
  if (index < 0) throw new Error(`Missing bank column: ${text}`);
  return index;
};
const chineseDate = value => /^\d{8}(?:\s|$)/.test(String(value ?? ''));

function finish(bank, account, rows, sheet, file, header, read, extras = {}) {
  if (!account) throw new Error(`Missing account in ${file}`);
  const statement = { bank, account: identifier(account), currency: 'CNY', file, sheet, ...extras, transactions: [] };
  for (let index = header + 1; index < rows.length; index++) {
    const raw = read(rows[index], index, statement);
    if (raw) statement.transactions.push(transaction(statement, raw, { file, sheet, row: index + 1 }));
  }
  if (!statement.transactions.length) throw new Error(`No transactions found in ${file} / ${sheet}`);
  statement.validation = validateStatement(statement);
  return statement;
}

const ccb = {
  name: 'ccb',
  detect: rows => headerAt(rows, ['借方发生额/元(支取)', '贷方发生额/元(收入)']),
  parse(rows, sheet, file, at) {
    const header = rows[at], f = text => field(header, text);
    return finish('建设银行', label(rows, '账号'), rows, sheet, file, at, row => {
      if (!chineseDate(row[f('交易时间')])) return null;
      return { date: row[f('交易时间')], outCents: cents(row[f('借方发生额/元(支取)')]), inCents: cents(row[f('贷方发生额/元(收入)')]), balanceCents: cents(row[f('余额')]), currency: yuan(row[f('币种')]), counterparty: row[f('对方户名')], counterAccount: row[f('对方账号')], memo: `${row[f('摘要')] ?? ''} ${row[f('备注')] ?? ''}`.trim(), reference: row[f('账户明细编号-交易流水号')] };
    }, { openingCents: cents(label(rows, '期初余额')), closingCents: cents(rows.find(row => h(row[0]) === '期初余额')?.[3]), declared: { incomeCents: cents(label(rows, '贷方发生额')), expenseCents: cents(label(rows, '借方发生额')), count: Number(rows.find(row => h(row[0]) === '借方发生额')?.[3]) + Number(rows.find(row => h(row[0]) === '贷方发生额')?.[3]) } });
  },
};

const pingAn = {
  name: 'ping-an', detect: rows => headerAt(rows, ['核心唯一流水号', '冲正标志', '账户余额']),
  parse(rows, sheet, file, at) {
    const header = rows[at], f = text => field(header, text);
    const actual = rows.slice(at + 1).filter(row => /^\d{14}$/.test(String(row[f('交易时间')])));
    const accounts = [...new Set(actual.map(row => identifier(row[f('账号')])) )];
    if (accounts.length !== 1) throw new Error('Ping An sheet contains multiple accounts');
    return finish('平安银行', accounts[0], rows, sheet, file, at, (row, index, statement) => {
      if (!/^\d{14}$/.test(String(row[f('交易时间')]))) {
        if (row.some(value => value != null && value !== '')) throw new Error(`Unrecognized Ping An row ${index + 1}`);
        return null;
      }
      let incoming = cents(row[f('收入')]), outgoing = cents(row[f('支出')]);
      const balance = cents(row[f('账户余额')]);
      if (incoming !== 0 && outgoing !== 0) {
        const prior = statement.transactions.at(-1)?.balanceCents;
        const delta = prior == null ? null : balance - prior;
        if (delta != null && delta === incoming && delta !== -outgoing) outgoing = 0;
        else if (delta != null && delta === -outgoing && delta !== incoming) incoming = 0;
        // This export uses the literal string "23" as a blank marker, also in the card column.
        // Prefer running-balance evidence; never treat an ordinary numeric 23 as blank.
        else if (prior == null && row[f('单位结算卡卡号')] === '23' && row[f('收入')] === '23' && outgoing !== 2300) incoming = 0;
        else if (prior == null && row[f('单位结算卡卡号')] === '23' && row[f('支出')] === '23' && incoming !== 2300) outgoing = 0;
        else throw new Error(`Ambiguous Ping An blank marker or amount at row ${index + 1}`);
      }
      return { date: row[f('交易时间')], inCents: incoming, outCents: outgoing, balanceCents: balance, currency: yuan(row[f('币种')]), counterparty: row[f('对方户名')], counterAccount: row[f('对方账号')], memo: [row[f('摘要')], row[f('交易用途')]].filter(value => value != null && String(value) !== '23').join(' '), reference: row[f('核心唯一流水号')] };
    });
  },
};

const cz = {
  name: 'czbank', detect: rows => headerAt(rows, ['借贷标志(1收2付)', '付款账号', '收款账号']),
  parse(rows, sheet, file, at) {
    const header = rows[at], f = text => field(header, text);
    const actual = rows.slice(at + 1).filter(row => /^\d{4}-\d{2}-\d{2}$/.test(String(row[f('交易日期')])));
    const accounts = [...new Set(actual.map(row => identifier(row[f(Number(row[f('借贷标志(1收2付)')]) === 1 ? '收款账号' : '付款账号')])) )];
    if (accounts.length !== 1) throw new Error('CZBank sheet contains multiple accounts');
    return finish('浙商银行', accounts[0], rows, sheet, file, at, row => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(String(row[f('交易日期')]))) {
        if (row.some(value => value != null && value !== '')) throw new Error('Unrecognized CZBank transaction row');
        return null;
      }
      const side = Number(row[f('借贷标志(1收2付)')]);
      if (![1, 2].includes(side)) throw new Error('Unknown CZBank debit/credit flag');
      return { date: row[f('交易日期')], inCents: side === 1 ? cents(row[f('交易金额')]) : 0, outCents: side === 2 ? cents(row[f('交易金额')]) : 0, counterAccount: row[f(side === 1 ? '付款账号' : '收款账号')], counterparty: row[f(side === 1 ? '付款人户名' : '收款人户名')], memo: row[f('备注')], reference: row[f('流水号')] };
    });
  },
};

const boc = {
  name: 'boc', detect: rows => rows.findIndex(row => row.some(cell => h(cell).startsWith('交易类型[')) && row.some(cell => h(cell).startsWith('交易后余额['))),
  parse(rows, sheet, file, at) {
    const header = rows[at], f = name => { const i = header.findIndex(cell => h(cell).startsWith(`${name}[`)); if (i < 0) throw new Error(`Missing BOC column ${name}`); return i; };
    const account = identifier(label(rows, '查询账号['));
    return finish('中国银行', account, rows, sheet, file, at, row => {
      if (!/^\d{8}$/.test(String(row[f('交易日期')]))) return null;
      const value = cents(row[f('交易金额')]);
      const ownPayer = identifier(row[f('付款人账号')]) === account, ownPayee = identifier(row[f('收款人账号')]) === account;
      if (ownPayer === ownPayee || (ownPayer && value >= 0) || (ownPayee && value <= 0)) throw new Error('BOC direction/account mismatch');
      return { date: row[f('交易日期')], inCents: value > 0 ? value : 0, outCents: value < 0 ? -value : 0, balanceCents: cents(row[f('交易后余额')]), currency: yuan(row[f('交易货币')]), counterAccount: row[f(ownPayer ? '收款人账号' : '付款人账号')], counterparty: row[f(ownPayer ? '收款人名称' : '付款人名称')], memo: [row[f('业务类型')], row[f('摘要')], row[f('用途')]].filter(Boolean).join(' '), reference: row[f('交易流水号')] };
    }, { declared: { count: Number(label(rows, '总笔数[')), expenseCents: cents(label(rows, '借方发生总额[')), incomeCents: cents(label(rows, '贷方发生总额[')) } });
  },
};

const cmb = {
  name: 'cmb', detect: rows => headerAt(rows, ['收(付)方账号', '借方金额', '贷方金额', '交易日']),
  parse(rows, sheet, file, at) {
    const header = rows[at], f = text => field(header, text), first = rows[at + 1];
    const opening = rows.find(row => h(row[3]) === '对账单期初余额');
    const declared = rows.find(row => h(row[0]) === '累计借金额');
    return finish('招商银行', first[f('账号')], rows, sheet, file, at, row => {
      if (!/^\d{4}-\d{2}-\d{2}$/.test(String(row[f('交易日')]))) return null;
      if (identifier(row[f('账号')]) !== identifier(first[f('账号')])) throw new Error('CMB sheet contains multiple accounts');
      return { date: row[f('交易日')], inCents: cents(row[f('贷方金额')]), outCents: cents(row[f('借方金额')]), balanceCents: cents(row[f('余额')]), currency: yuan(row[f('币种')]), counterparty: row[f('收(付)方名称')], counterAccount: row[f('收(付)方账号')], memo: `${row[f('摘要')] ?? ''} ${row[f('交易类型')] ?? ''}`.trim(), reference: row[f('流水号')] };
    }, { openingCents: cents(opening?.[4]), closingCents: cents(opening?.[7]), declared: { incomeCents: cents(declared?.[7]), expenseCents: cents(declared?.[1]), count: Number(declared?.[4]) + Number(rows.find(row => h(row[0]) === '累计贷总笔数')?.[1]) } });
  },
};

export const bankAdapters = [pingAn, ccb, cz, boc, cmb];

export function parseBankSheet(rows, sheet, file) {
  const matches = bankAdapters.map(adapter => ({ adapter, at: adapter.detect(rows) })).filter(match => match.at >= 0);
  if (matches.length !== 1) throw new Error(`Unsupported or ambiguous bank format: ${file} / ${sheet}`);
  const { adapter, at } = matches[0];
  const statement = adapter.parse(rows, sheet, file, at);
  statement.adapter = adapter.name;
  return statement;
}
