import { test, expect, type APIRequestContext } from '@playwright/test'

const backend = process.env.E2E_BACKEND_URL || 'http://127.0.0.1:8080'
const userId = '00000000-0000-4000-8000-000000000099'
const auth = { 'X-User-Id': userId }

async function json<T>(request: APIRequestContext, path: string, init: Parameters<APIRequestContext['fetch']>[1] = {}): Promise<T> {
  let response: Awaited<ReturnType<APIRequestContext['fetch']>>
  let text = ''
  for (let attempt = 0; attempt < 4; attempt += 1) {
    response = await request.fetch(`${backend}/v1${path}`, { ...init, headers: { ...auth, ...(init.headers || {}) } })
    if (response.ok()) return response.json() as Promise<T>
    text = await response.text()
    // The balance worker and voucher writes contend on the ledger row; retry transient deadlocks.
    if (!/死锁|Deadlock|PessimisticLockingFailure/.test(text)) break
    await new Promise((resolve) => setTimeout(resolve, 600 * (attempt + 1)))
  }
  expect(response!.ok(), text).toBeTruthy()
  return response!.json() as Promise<T>
}

async function reportStatus(request: APIRequestContext, ledgerId: string, periodCode: string): Promise<number> {
  const response = await request.fetch(`${backend}/v1/ledgers/${ledgerId}/reports/statutory/cash-flow?periodCode=${periodCode}`, { headers: auth })
  return response.status()
}

test('cash-flow report: navigate, switch period, view quality alert, jump to voucher and enter formula adjustment', async ({ page, request }) => {
  await page.addInitScript((id) => sessionStorage.setItem('ai-accounting.session', JSON.stringify({ localUserId: id })), userId)

  // Seed a fresh SME/CNY ledger with opening balances and a classified external cash inflow.
  const ledger = await json<{ id: string }>(request, '/ledgers', {
    method: 'POST',
    data: { name: `CF-E2E ${Date.now()}`, accountingStandardCode: 'SME', accountingStandardVersion: 'v1', baseCurrency: 'CNY', startDate: '2026-01-01', approvalEnabled: false },
  })
  const accounts = await json<Array<{ id: string; code: string }>>(request, `/ledgers/${ledger.id}/accounts`)
  const periods = await json<Array<{ id: string; periodCode: string }>>(request, `/ledgers/${ledger.id}/periods`)
  const cash = accounts.find((account) => account.code === '1001')
  const capital = accounts.find((account) => account.code === '3001')
  const revenue = accounts.find((account) => account.code === '5001')
  const period = periods.find((item) => item.periodCode === '2026-01')
  expect(cash && capital && revenue && period).toBeTruthy()

  await json(request, `/ledgers/${ledger.id}/opening-balances`, {
    method: 'PUT',
    data: { lines: [
      { accountId: cash!.id, periodId: period!.id, currency: 'CNY', dimensionKey: '', debitOriginal: '100', creditOriginal: '0', exchangeRate: '1' },
      { accountId: capital!.id, periodId: period!.id, currency: 'CNY', dimensionKey: '', debitOriginal: '0', creditOriginal: '100', exchangeRate: '1' },
    ] },
  })
  await json(request, `/ledgers/${ledger.id}/opening-balances:confirm`, { method: 'POST' })

  const items = await json<Array<{ id: string; code: string }>>(request, `/ledgers/${ledger.id}/cash-flow-items`)
  const salesItem = items.find((item) => item.code === 'SME_CF_01_SALES_RECEIPTS')
  expect(salesItem).toBeTruthy()
  const voucher = await json<{ id: string }>(request, `/ledgers/${ledger.id}/vouchers`, {
    method: 'POST',
    headers: { 'Idempotency-Key': `cf-e2e-${Date.now()}` },
    data: {
      periodId: period!.id, voucherDate: '2026-01-15', voucherType: '记', voucherNumber: '1', summary: '销售收款',
      lines: [
        { accountId: cash!.id, side: 'DEBIT', currency: 'CNY', originalAmount: '300', exchangeRate: '1', cashFlowItemId: salesItem!.id, summary: '收款' },
        { accountId: revenue!.id, side: 'CREDIT', currency: 'CNY', originalAmount: '300', exchangeRate: '1', summary: '收入' },
      ],
    },
  })
  // create() auto-validates and posts for non-approval ledgers.
  // The posted cash line already carries its classification.

  // Wait for the statutory projection to become ready for both periods.
  await expect.poll(async () => reportStatus(request, ledger.id, '2026-01'), { timeout: 45_000 }).toBe(200)
  await expect.poll(async () => reportStatus(request, ledger.id, '2026-02'), { timeout: 45_000 }).toBe(200)

  // 1. The cash-flow report renders the continuous statement with complete data.
  await page.goto(`/ledgers/${ledger.id}/reports/cash-flow?periodCode=2026-01`)
  await expect(page.getByRole('heading', { name: '现金流量表' })).toBeVisible()
  await expect(page.getByText('小企业会计准则 · CNY')).toBeVisible()
  await expect(page.getByText('公式版本 v1')).toBeVisible()
  await expect(page.getByText('一、经营活动产生的现金流量')).toBeVisible()
  await expect(page.getByText('销售产成品、商品、提供劳务收到的现金')).toBeVisible()
  await expect(page.getByText('五、期末现金余额')).toBeVisible()
  // 期初 100 + 流入 300 → 期末现金余额 400.00 in both columns.
  await expect(page.getByText('400.00')).toHaveCount(2)
  await expect(page.getByText('数据完整')).toBeVisible()

  // 2. Switch the accounting period and keep the report rendering.
  const periodSelector = page.locator('div.ant-select', { has: page.locator('input[aria-label="会计期间"]') })
  await periodSelector.click()
  await page.getByText('2026年第2期').click()
  await expect(page.getByRole('heading', { name: '现金流量表' })).toBeVisible()
  await expect(page.getByText('五、期末现金余额')).toBeVisible()
  await expect(page.getByText('数据完整')).toBeVisible()

  // 3. 调整公式 jumps to the formula settings preselected for CASH_FLOW.
  await page.goto(`/ledgers/${ledger.id}/reports/cash-flow?periodCode=2026-01`)
  await page.getByRole('button', { name: '调整公式' }).click()
  await expect(page).toHaveURL(new RegExp(`/ledgers/${ledger.id}/settings/report-formulas\\?formula=CASH_FLOW`))
  await expect(page.getByRole('heading', { name: '账套设置' })).toBeVisible()
  await expect(page.getByText('当前发布版本 v1')).toBeVisible()
  await expect(page.getByLabel('第 1 行项目名称')).toHaveValue('销售产成品、商品、提供劳务收到的现金')

  // 4. Publish a formula that no longer references the posted item, then verify the
  //    INCOMPLETE alert, the located sample and the voucher jump.
  const workspace = await json<{ publishedDefinition: { groups: { lines: { key: string; name: string; expression: unknown }[] }[] } }>(request, `/ledgers/${ledger.id}/report-formulas/CASH_FLOW`)
  const cf1 = workspace.publishedDefinition.groups.flatMap((group) => group.lines).find((line) => line.key === 'cf-1')
  expect(cf1).toBeTruthy()
  const draft = await json<{ version: number }>(request, `/ledgers/${ledger.id}/report-formulas/CASH_FLOW/draft`, { method: 'POST' })
  const saved = await json<{ version: number }>(request, `/ledgers/${ledger.id}/report-formulas/CASH_FLOW/draft`, {
    method: 'PUT',
    data: {
      expectedDraftVersion: draft.version,
      lines: [{
        lineKey: 'cf-1',
        name: cf1!.name,
        // Replace the item-referencing expression with an empty combination so the posted
        // SME_CF_01_SALES_RECEIPTS item is no longer referenced by the formula.
        expression: { type: 'LINEAR_COMBINATION', components: [] },
      }],
    },
  })
  await json(request, `/ledgers/${ledger.id}/report-formulas/CASH_FLOW/draft:preview`, {
    method: 'POST',
    data: { expectedDraftVersion: saved.version, periodCode: '2026-01', periodFrom: null, periodTo: null },
  })
  await json(request, `/ledgers/${ledger.id}/report-formulas/CASH_FLOW:publish`, {
    method: 'POST',
    data: { expectedPublishedVersion: 1, expectedDraftVersion: saved.version, acknowledgeWarnings: true },
  })

  await page.goto(`/ledgers/${ledger.id}/reports/cash-flow?periodCode=2026-01`)
  await expect(page.getByText('公式版本 v2')).toBeVisible()
  await expect(page.getByText('存在未分类的现金收支')).toBeVisible()
  await expect(page.getByText(/本年累计：\s*1 张凭证 \/ 1 行；\s*本月：\s*1 张凭证 \/ 1 行/)).toBeVisible()
  // The dropped item also breaks the cash reconciliation (balances still include the flow).
  await expect(page.getByText('勾稽检查未通过')).toBeVisible()
  await page.getByText(/查看定位样例/).click()
  await expect(page.getByText('项目未被当前报表公式引用')).toBeVisible()
  await page.getByRole('link', { name: '1' }).click()
  await expect(page).toHaveURL(new RegExp(`/ledgers/${ledger.id}/vouchers/${voucher.id}`))
  await expect(page.getByRole('heading', { name: '记账凭证' })).toBeVisible()
  await expect(page.getByText('凭证号 记-1')).toBeVisible()
  // The posted cash line shows its now-stale classification (formula-derived cash account).
  await expect(page.getByRole('combobox', { name: '第 1 条分录现金流项目' })).toBeVisible()
  await expect(page.getByText(/SME_CF_01_SALES_RECEIPTS 销售产成品、商品、提供劳务收到的现金（已停用或不在公式中）/)).toBeVisible()
})
