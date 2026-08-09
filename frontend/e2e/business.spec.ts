import { test, expect, type APIRequestContext } from '@playwright/test'

const backend = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080'
const userId = '00000000-0000-4000-8000-000000000099'
const auth = { 'X-User-Id': userId }

async function json<T>(request: APIRequestContext, path: string, init: Parameters<APIRequestContext['fetch']>[1] = {}): Promise<T> {
  const response = await request.fetch(`${backend}/v1${path}`, { ...init, headers: { ...auth, ...(init.headers || {}) } })
  expect(response.ok(), await response.text()).toBeTruthy()
  return response.json() as Promise<T>
}

test.skip('creates, restores, posts a voucher and verifies report totals', async ({ page, request }) => {
  await page.addInitScript((id) => sessionStorage.setItem('ai-accounting.session', JSON.stringify({ localUserId: id })), userId)
  const ledger = await json<{ id: string }>(request, '/ledgers', { method: 'POST', data: { name: `E2E ${Date.now()}`, accountingStandardCode: 'SME', accountingStandardVersion: 'v1', baseCurrency: 'CNY', startDate: '2026-01-01', approvalEnabled: false } })
  const accounts = await json<Array<{ id: string; code: string }>>(request, `/ledgers/${ledger.id}/accounts`)
  const periods = await json<Array<{ id: string; periodCode: string }>>(request, `/ledgers/${ledger.id}/periods`)
  const cash = accounts.find((account) => account.code === '1001')
  const capital = accounts.find((account) => account.code === '3001')
  const period = periods.find((item) => item.periodCode === '2026-01')
  expect(cash && capital && period).toBeTruthy()

  await json(request, `/ledgers/${ledger.id}/accounts`, { method: 'POST', data: {
    code: '560101', name: '管理费用-办公费', category: 'EXPENSE', normalBalance: 'DEBIT',
    cashFlowRequired: false, quantityEnabled: false, dimensionRequirements: [],
  } })
  await page.goto(`/ledgers/${ledger.id}/settings/accounts`)
  await expect(page.getByRole('heading', { name: '账套设置' })).toBeVisible()
  await expect(page.getByText('管理费用-办公费')).toBeVisible()
  await expect(page.getByRole('button', { name: '新增一级科目' })).toBeVisible()

  await json(request, `/ledgers/${ledger.id}/opening-balances`, { method: 'PUT', data: { lines: [{ accountId: cash!.id, periodId: period!.id, currency: 'CNY', dimensionKey: '', debitOriginal: '100', creditOriginal: '0', exchangeRate: '1' }, { accountId: capital!.id, periodId: period!.id, currency: 'CNY', dimensionKey: '', debitOriginal: '0', creditOriginal: '100', exchangeRate: '1' }] } })
  await page.goto(`/ledgers/${ledger.id}/settings/openings`)
  await expect(page.getByRole('heading', { name: '账套设置' })).toBeVisible()
  await expect(page.getByText(/借方合计/)).toBeVisible()
  await page.getByRole('button', { name: '确认期初余额' }).click()
  await expect(page.getByText('确认后将无法继续编辑或导入期初余额。')).toBeVisible()
  await page.getByRole('dialog').getByRole('button', { name: /确\s*认/ }).click()
  await expect.poll(async () => { const confirmed = await json<Array<{ confirmed: boolean }>>(request, `/ledgers/${ledger.id}/opening-balances`); return confirmed.some((row) => row.confirmed) }).toBeTruthy()

  const voucher = await json<{ id: string; version: number }>(request, `/ledgers/${ledger.id}/vouchers`, { method: 'POST', headers: { 'Idempotency-Key': `e2e-${Date.now()}` }, data: { periodId: period!.id, voucherDate: '2026-01-15', voucherType: 'GENERAL', voucherNumber: '1', summary: 'E2E 原始摘要', lines: [{ accountId: cash!.id, side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', summary: 'cash' }, { accountId: capital!.id, side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', summary: 'capital' }] } })
  await json(request, `/ledgers/${ledger.id}/vouchers/${voucher.id}`, { method: 'PUT', data: { expectedVersion: voucher.version, periodId: period!.id, voucherDate: '2026-01-15', voucherType: 'GENERAL', voucherNumber: '1', summary: 'E2E 待恢复摘要', lines: [{ accountId: cash!.id, side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', summary: 'cash' }, { accountId: capital!.id, side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', summary: 'capital' }] } })

  await page.goto(`/ledgers/${ledger.id}/vouchers/${voucher.id}`)
  await expect(page.getByText('历史版本')).toBeVisible()
  await page.getByRole('button', { name: /恢\s*复/ }).first().click()
  await page.locator('.ant-modal-confirm-btns .ant-btn-primary').click()
  await expect.poll(async () => (await json<{ summary: string }>(request, `/ledgers/${ledger.id}/vouchers/${voucher.id}`)).summary).toBe('E2E 原始摘要')
  await expect(page.getByLabel('摘要')).toHaveValue('E2E 原始摘要')
  await page.getByLabel('摘要').fill('E2E 第一次保存')
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(async () => (await json<{ summary: string }>(request, `/ledgers/${ledger.id}/vouchers/${voucher.id}`)).summary).toBe('E2E 第一次保存')
  await page.getByLabel('摘要').fill('E2E 第二次保存')
  await page.getByRole('button', { name: '保存草稿' }).click()
  await expect.poll(async () => (await json<{ summary: string }>(request, `/ledgers/${ledger.id}/vouchers/${voucher.id}`)).summary).toBe('E2E 第二次保存')
  await page.getByRole('button', { name: /校\s*验/ }).click()
  await page.getByRole('button', { name: /^记\s*账$/ }).click()
  await expect(page.getByText(/POSTED/)).toBeVisible()

  const report = await json<Array<{ code: string; balance: number | string }>>(request, `/ledgers/${ledger.id}/reports/trial-balance?periodCode=2026-01`)
  expect(Number(report.find((line) => line.code === '1001')?.balance)).toBe(200)
  await page.goto(`/ledgers/${ledger.id}/reports/trial-balance?periodCode=2026-01`)
  await expect(page.getByRole('heading', { name: '科目余额表' })).toBeVisible()
})
