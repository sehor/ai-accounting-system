import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import { parseArgs } from 'node:util';
import { parseBankSheet } from './bank-adapters.mjs';
import { cents, identifier, dateOnly, digest, normalizeHeader, deduplicateTransactions, matchTransfers, classify, reconcileVouchers } from './source-core.mjs';

export { classify, reconcileVouchers } from './source-core.mjs';

async function artifactTool(override) {
  if (override) return import(pathToFileURL(path.resolve(override)).href);
  const dependencyRoot = path.join(os.homedir(), '.cache', 'codex-runtimes', 'codex-primary-runtime', 'dependencies', 'node');
  const require = createRequire(path.join(dependencyRoot, 'package.json'));
  try { return await import(pathToFileURL(require.resolve('@oai/artifact-tool')).href); }
  catch (error) { throw new Error('Bundled artifact-tool unavailable. Use load_workspace_dependencies and pass artifactModule / --artifact-module.', { cause: error }); }
}

export function parseInvoiceSheet(rows, sheet, file) {
  const headerIndex = rows.findIndex(row => ['销方名称', '购买方名称', '金额', '税额', '价税合计'].every(name => row.some(cell => normalizeHeader(cell) === name)));
  if (headerIndex < 0) throw new Error(`Unsupported invoice summary: ${file} / ${sheet}`);
  const header = rows[headerIndex], col = name => header.findIndex(cell => normalizeHeader(cell) === name);
  const records = [], issues = [];
  for (let i = headerIndex + 1; i < rows.length; i++) {
    const raw = rows[i];
    if (raw.every(cell => cell == null || cell === '')) continue;
    const get = name => col(name) < 0 ? null : raw[col(name)];
    const record = {
      source: { file, sheet, row: i + 1 }, invoiceNumber: identifier(get('数电发票号码') || get('发票号码')),
      seller: String(get('销方名称') ?? ''), buyer: String(get('购买方名称') ?? ''), date: dateOnly(get('开票日期')),
      item: String(get('货物或应税劳务名称') ?? ''), netCents: cents(get('金额')), taxCents: cents(get('税额')), grossCents: cents(get('价税合计')),
      taxRate: get('税率'), invoiceType: get('发票票种'), status: get('发票状态'), positive: get('是否正数发票'), note: get('备注'),
    };
    if (!record.seller || !record.buyer) issues.push({ type: 'missing_invoice_party', source: record.source });
    if (record.netCents + record.taxCents !== record.grossCents) issues.push({ type: 'invoice_amount_mismatch', source: record.source });
    if (record.status !== '正常' || record.positive === '否') issues.push({ type: 'invoice_status_review', source: record.source, status: record.status, positive: record.positive });
    records.push(record);
  }
  return { records, issues };
}

export async function readSources(files, options = {}) {
  const kind = options.kind || 'bank';
  if (!['bank', 'invoices'].includes(kind) || !Array.isArray(files) || !files.length) throw new Error('Provide kind bank/invoices and at least one file');
  const { FileBlob, SpreadsheetFile } = await artifactTool(options.artifactModule);
  const statements = [], invoiceRows = [], issues = [], sources = [], seenFiles = new Set();
  for (const input of files) {
    const file = path.resolve(input);
    if (path.extname(file).toLowerCase() !== '.xlsx') throw new Error(`Only .xlsx is supported; convert legacy .xls first: ${file}`);
    const sha256 = digest(await fs.readFile(file));
    if (seenFiles.has(sha256)) { issues.push({ type: 'duplicate_source_file', file, sha256 }); continue; }
    seenFiles.add(sha256); sources.push({ file, sha256 });
    const workbook = await SpreadsheetFile.importXlsx(await FileBlob.load(file));
    if (kind === 'bank') {
      for (const sheet of workbook.worksheets.items) {
        const rows = sheet.getUsedRange()?.values || [];
        if (!rows.some(row => row.some(cell => cell != null && cell !== ''))) continue;
        const statement = parseBankSheet(rows, sheet.name, file);
        statements.push(statement); issues.push(...statement.validation.issues.map(issue => ({ ...issue, file, sheet: sheet.name })));
      }
    } else {
      const summaries = workbook.worksheets.items.filter(sheet => {
        const rows = sheet.getUsedRange()?.values || [];
        return rows.some(row => row.some(cell => normalizeHeader(cell) === '价税合计') && row.some(cell => normalizeHeader(cell) === '金额'));
      });
      if (summaries.length !== 1) throw new Error(`Expected exactly one invoice amount summary in ${file}; review sheets instead of double-counting them`);
      const sheet = summaries[0], result = parseInvoiceSheet(sheet.getUsedRange().values, sheet.name, file);
      invoiceRows.push(...result.records); issues.push(...result.issues);
    }
  }
  if (kind === 'invoices') return {
    schemaVersion: 1, kind, sources, records: invoiceRows, issues,
    summary: { rows: invoiceRows.length, invoices: invoiceRows.every(r => r.invoiceNumber) ? new Set(invoiceRows.map(r => `${r.seller}:${r.invoiceNumber}`)).size : null, netCents: invoiceRows.reduce((s, r) => s + r.netCents, 0), taxCents: invoiceRows.reduce((s, r) => s + r.taxCents, 0), grossCents: invoiceRows.reduce((s, r) => s + r.grossCents, 0), buyers: [...new Set(invoiceRows.map(r => r.buyer))] },
  };
  const unique = deduplicateTransactions(statements.flatMap(s => s.transactions));
  const transfers = matchTransfers(unique.transactions);
  issues.push(...unique.issues, ...transfers.issues);
  const classifications = classify(unique.transactions, transfers.pairs, options.rules || [], options.accounts || []);
  const summary = statements.map(s => ({ bank: s.bank, account: s.account, currency: s.currency, ...s.validation, issues: s.validation.issues.length }));
  return { schemaVersion: 1, kind, sources, statements, transactions: unique.transactions, transfers: transfers.pairs, classifications, issues, summary };
}

async function main() {
  const { values, positionals } = parseArgs({ allowPositionals: true, options: { summary: { type: 'boolean' }, output: { type: 'string' }, 'artifact-module': { type: 'string' }, stdin: { type: 'boolean' } } });
  let request;
  if (values.stdin) {
    let text = ''; for await (const chunk of process.stdin) text += chunk;
    request = JSON.parse(text);
  } else request = { kind: positionals[0], files: positionals.slice(1) };
  const result = await readSources(request.files, { ...request, artifactModule: values['artifact-module'] || request.artifactModule });
  if (request.vouchers) result.reconciliation = reconcileVouchers(result.transactions, request.vouchers, request.bankAccounts || {});
  const output = values.summary ? { kind: result.kind, summary: result.summary, transferCount: result.transfers?.length, unresolved: result.classifications?.filter(c => c.status === 'needs_review' || c.status === 'conflicting_rules').length, issues: result.issues, reconciliation: result.reconciliation } : result;
  const text = JSON.stringify(output) + '\n';
  if (values.output) {
    const outputPath = path.resolve(values.output);
    const realOutput = await fs.realpath(outputPath).catch(error => { if (error.code === 'ENOENT') return outputPath; throw error; });
    for (const source of result.sources) {
      if ((await fs.realpath(source.file)).toLowerCase() === realOutput.toLowerCase()) throw new Error('Output must not overwrite a source workbook');
    }
    await fs.writeFile(outputPath, text);
  }
  else process.stdout.write(text);
  if (result.issues.some(issue => !['duplicate_source_file', 'duplicate_transaction', 'invoice_status_review'].includes(issue.type)) || result.reconciliation?.ok === false) process.exitCode = 2;
}

if (typeof process !== 'undefined' && process.argv[1] && import.meta.url === pathToFileURL(path.resolve(process.argv[1])).href) main().catch(error => { process.stderr.write(error.message + '\n'); process.exitCode = 1; });
