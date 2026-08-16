import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { formatReportAmount, reportRowKey, ReportsPage } from './ReportsPage'
import { apiFetch, ApiError } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

installLegacyOpenApiBridge(apiFetch as never)

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })),
})

beforeAll(() => {
  const getComputedStyle = window.getComputedStyle
  window.getComputedStyle = ((element: Element) => getComputedStyle(element)) as typeof window.getComputedStyle
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

describe('reportRowKey', () => {
  it('distinguishes multiple ledger lines from the same voucher', () => {
    const line = { voucherId: 'voucher-1', voucherNumber: '1', voucherDate: '2026-01-01', accountCode: '1001', accountName: '现金', side: 'DEBIT', amount: '100', dimensionKey: null }

    expect(reportRowKey(line, 0)).not.toBe(reportRowKey(line, 1))
  })
})

describe('formatReportAmount', () => {
  it('leaves zero values blank and formats Chinese financial amounts', () => {
    expect(formatReportAmount('0')).toBe('')
    expect(formatReportAmount('-1234.5')).toBe('-1,234.50')
    expect(formatReportAmount(2206174.35)).toBe('2,206,174.35')
  })
})

const detail = (key: string, lineNo: number, primaryAmount = '0', comparativeAmount = '0'): {
  key: string; lineNo: number; name: string; indent: number; rowType: string; primaryAmount: string; comparativeAmount: string
} => ({
  key, lineNo, name: `项目 ${lineNo}`, indent: 0, rowType: 'DETAIL',
  primaryAmount, comparativeAmount,
})

function cashFlowStatement(overrides: Record<string, unknown> = {}) {
  const groups = [
    {
      key: 'OPERATING', title: '一、经营活动产生的现金流量',
      lines: [
        detail('cf-1', 1, '1000.5', '200.25'),
        detail('cf-2', 2),
        detail('cf-3', 3),
        detail('cf-4', 4),
        detail('cf-5', 5),
        detail('cf-6', 6),
        { key: 'cf-7', lineNo: 7, name: '经营活动产生的现金流量净额', indent: 0, rowType: 'TOTAL', primaryAmount: '900.5', comparativeAmount: '200.25' },
      ],
    },
    {
      key: 'INVESTING', title: '二、投资活动产生的现金流量',
      lines: [
        detail('cf-8', 8),
        detail('cf-9', 9),
        { key: 'cf-10', lineNo: 10, name: '处置固定资产收回的现金净额', indent: 0, rowType: 'DETAIL', primaryAmount: '-100', comparativeAmount: '0' },
        detail('cf-11', 11),
        detail('cf-12', 12),
        { key: 'cf-13', lineNo: 13, name: '投资活动产生的现金流量净额', indent: 0, rowType: 'TOTAL', primaryAmount: '-50', comparativeAmount: '0' },
      ],
    },
    {
      key: 'FINANCING', title: '三、筹资活动产生的现金流量',
      lines: [
        detail('cf-14', 14),
        detail('cf-15', 15),
        detail('cf-16', 16),
        detail('cf-17', 17),
        detail('cf-18', 18),
        { key: 'cf-19', lineNo: 19, name: '筹资活动产生的现金流量净额', indent: 0, rowType: 'TOTAL', primaryAmount: '0', comparativeAmount: '0' },
      ],
    },
    {
      key: 'BALANCES', title: '四、现金净增加额及现金余额',
      lines: [
        { key: 'cf-20', lineNo: 20, name: '四、现金净增加额', indent: 0, rowType: 'TOTAL', primaryAmount: '900.5', comparativeAmount: '200.25' },
        detail('cf-21', 21, '5000', '5200'),
        { key: 'cf-22', lineNo: 22, name: '五、期末现金余额', indent: 0, rowType: 'TOTAL', primaryAmount: '5900.5', comparativeAmount: '5400.25' },
      ],
    },
  ]
  return {
    reportType: 'cash-flow',
    templateCode: 'SME-2011-17-CASH-FLOW',
    standardCode: 'SME',
    standardVersion: '2011-17',
    periodCode: '2026-08',
    primaryColumn: '本年累计金额',
    comparativeColumn: '本月金额',
    formulaCode: 'CASH_FLOW',
    formulaVersion: 3,
    groups,
    checks: [],
    dataQuality: {
      status: 'COMPLETE',
      primaryUnclassifiedVoucherCount: 0,
      primaryUnclassifiedLineCount: 0,
      comparativeUnclassifiedVoucherCount: 0,
      comparativeUnclassifiedLineCount: 0,
      samples: [],
    },
    ...overrides,
  }
}

function installBackend(
  ledger = { accountingStandardCode: 'SME', baseCurrency: 'CNY' },
  onReport?: () => unknown,
  periods = [{ id: 'period-8', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: true }],
) {
  const mock = vi.fn(async (path: string) => {
    if (path === '/ledgers/ledger-1') return { id: 'ledger-1', name: '测试账套', accountingStandardCode: ledger.accountingStandardCode, accountingStandardVersion: '2011-17', baseCurrency: ledger.baseCurrency, startDate: '2026-01-01', approvalEnabled: false, status: 'ACTIVE', description: '' }
    if (path === '/ledgers/ledger-1/periods') return periods
    if (path.includes('/reports/statutory/')) return onReport ? onReport() : cashFlowStatement()
    return []
  })
  ;(apiFetch as unknown as ReturnType<typeof vi.fn>).mockImplementation(mock)
  return mock
}

function renderPage(initial = '/ledgers/ledger-1/reports/cash-flow') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={[initial]}>
          <Routes>
            <Route path="/ledgers/:ledgerId/reports/:reportType" element={<ReportsPage />} />
            <Route path="/ledgers/:ledgerId/settings/report-formulas" element={<output aria-label="公式设置">公式设置页</output>} />
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
  return client
}

describe('ReportsPage cash-flow', () => {
  it('renders the continuous 22-line statement with four grey section rows and both columns', async () => {
    installBackend()
    renderPage()

    await waitFor(() => expect(screen.getByText('现金流量表')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByText('公式版本 v3')).toBeInTheDocument())

    for (const title of ['一、经营活动产生的现金流量', '二、投资活动产生的现金流量', '三、筹资活动产生的现金流量', '四、现金净增加额及现金余额']) {
      expect(screen.getByText(title)).toBeInTheDocument()
    }

    const table = document.querySelector('.cash-flow-statement-table')
    expect(table).not.toBeNull()
    const head = table!.querySelector('thead')
    expect(head).not.toBeNull()
    const headers = Array.from(head!.querySelectorAll('th')).map((th) => th.textContent)
    expect(headers).toEqual(['项目', '行次', '本年累计金额', '本月金额'])

    const rows = Array.from(table!.querySelectorAll('tbody tr'))
    // 22 statement lines + 4 group section rows.
    expect(rows).toHaveLength(26)
    // 5 total rows are bolded via the total class.
    expect(table!.querySelectorAll('tbody tr.statutory-row-total')).toHaveLength(5)
    // All 22 line numbers appear.
    for (let lineNo = 1; lineNo <= 22; lineNo += 1) {
      expect(within(table as HTMLElement).getByText(String(lineNo))).toBeInTheDocument()
    }

    expect(screen.getByText('1,000.50')).toBeInTheDocument()
    expect(screen.getByText('-100.00')).toBeInTheDocument()
    expect(screen.getByText('5,900.50')).toBeInTheDocument()
    expect(screen.getByText('数据完整')).toBeInTheDocument()
  })

  it('keeps zero amounts blank inside the table', async () => {
    installBackend()
    renderPage()
    await waitFor(() => expect(document.querySelector('.cash-flow-statement-table')).not.toBeNull())
    const table = document.querySelector('.cash-flow-statement-table')!
    const row = Array.from(table.querySelectorAll('tbody tr')).find((tr) => tr.textContent?.includes('项目 2'))!
    const cells = Array.from(row.querySelectorAll('td')).map((td) => td.textContent)
    expect(cells[2]).toBe('')
    expect(cells[3]).toBe('')
  })

  it('shows the INCOMPLETE warning with per-column counts and expandable located samples', async () => {
    installBackend({ accountingStandardCode: 'SME', baseCurrency: 'CNY' }, () => cashFlowStatement({
      dataQuality: {
        status: 'INCOMPLETE',
        primaryUnclassifiedVoucherCount: 2,
        primaryUnclassifiedLineCount: 3,
        comparativeUnclassifiedVoucherCount: 1,
        comparativeUnclassifiedLineCount: 1,
        samples: [
          { voucherId: 'v-1', voucherNumber: '记-12', periodCode: '2026-08', voucherDate: '2026-08-15', lineNo: 1, side: 'DEBIT', baseAmount: '1000.00', reason: 'ITEM_MISSING' },
          { voucherId: 'v-2', voucherNumber: '记-13', periodCode: '2026-08', voucherDate: '2026-08-16', lineNo: 2, side: 'CREDIT', baseAmount: '-50.00', reason: 'ITEM_NOT_IN_FORMULA' },
        ],
      },
    }))
    renderPage()

    await waitFor(() => expect(screen.getByText('存在未分类的现金收支')).toBeInTheDocument())
    expect(screen.getByText(/本年累计：\s*2 张凭证 \/ 3 行；\s*本月：\s*1 张凭证 \/ 1 行/)).toBeInTheDocument()
    expect(screen.queryByText('数据完整')).not.toBeInTheDocument()

    fireEvent.click(screen.getByText('查看定位样例（最多 2 条）'))
    await waitFor(() => expect(screen.getByText('未填写现金流项目')).toBeInTheDocument())
    expect(screen.getByText('项目未被当前报表公式引用')).toBeInTheDocument()
    const voucherLink = screen.getByRole('link', { name: '记-12' })
    expect(voucherLink).toHaveAttribute('href', '/ledgers/ledger-1/vouchers/v-1')
  })

  it('maps all four sample reasons to Chinese labels', async () => {
    installBackend({ accountingStandardCode: 'SME', baseCurrency: 'CNY' }, () => cashFlowStatement({
      dataQuality: {
        status: 'INCOMPLETE',
        primaryUnclassifiedVoucherCount: 4,
        primaryUnclassifiedLineCount: 4,
        comparativeUnclassifiedVoucherCount: 0,
        comparativeUnclassifiedLineCount: 0,
        samples: [
          { voucherId: 'v-1', voucherNumber: '1', periodCode: '2026-08', voucherDate: '2026-08-15', lineNo: 1, side: 'DEBIT', baseAmount: '1.00', reason: 'ITEM_MISSING' },
          { voucherId: 'v-2', voucherNumber: '2', periodCode: '2026-08', voucherDate: '2026-08-15', lineNo: 1, side: 'DEBIT', baseAmount: '1.00', reason: 'LEGACY_COARSE_ITEM' },
          { voucherId: 'v-3', voucherNumber: '3', periodCode: '2026-08', voucherDate: '2026-08-15', lineNo: 1, side: 'DEBIT', baseAmount: '1.00', reason: 'ITEM_NOT_IN_FORMULA' },
          { voucherId: 'v-4', voucherNumber: '4', periodCode: '2026-08', voucherDate: '2026-08-15', lineNo: 1, side: 'DEBIT', baseAmount: '1.00', reason: 'ITEM_INACTIVE' },
        ],
      },
    }))
    renderPage()
    await waitFor(() => expect(screen.getByText('存在未分类的现金收支')).toBeInTheDocument())
    fireEvent.click(screen.getByText('查看定位样例（最多 4 条）'))
    await waitFor(() => expect(screen.getByText('未填写现金流项目')).toBeInTheDocument())
    expect(screen.getByText('使用旧的三分类项目')).toBeInTheDocument()
    expect(screen.getByText('项目已停用')).toBeInTheDocument()
  })

  it('shows failed reconciliation checks separately from data completeness', async () => {
    installBackend({ accountingStandardCode: 'SME', baseCurrency: 'CNY' }, () => cashFlowStatement({
      checks: [
        { key: 'c1', name: '行22 = 行20 + 行21（本年累计）', passed: false, difference: '1.50' },
        { key: 'c2', name: '行7 经营净额（本月）', passed: true, difference: '0.00' },
      ],
      dataQuality: { status: 'COMPLETE', primaryUnclassifiedVoucherCount: 0, primaryUnclassifiedLineCount: 0, comparativeUnclassifiedVoucherCount: 0, comparativeUnclassifiedLineCount: 0, samples: [] },
    }))
    renderPage()
    await waitFor(() => expect(screen.getByText('勾稽检查未通过')).toBeInTheDocument())
    expect(screen.getByText(/行22 = 行20 \+ 行21（本年累计），差额 1.50/)).toBeInTheDocument()
    expect(screen.queryByText(/行7 经营净额（本月）/)).not.toBeInTheDocument()
    // Data completeness stays a separate success status.
    expect(screen.getByText('数据完整')).toBeInTheDocument()
  })

  it('jumps to the formula settings preselected for CASH_FLOW via 调整公式', async () => {
    installBackend()
    renderPage()
    await waitFor(() => expect(screen.getByRole('button', { name: '调整公式' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '调整公式' }))
    await waitFor(() => expect(screen.getByLabelText('公式设置')).toBeInTheDocument())
  })

  it('shows the backend standard error for non-SME ledgers without falling back to the legacy endpoint', async () => {
    installBackend({ accountingStandardCode: 'CAS', baseCurrency: 'CNY' })
    ;(apiFetch as unknown as ReturnType<typeof vi.fn>).mockImplementation(async (path: string) => {
      if (path === '/ledgers/ledger-1') return { id: 'ledger-1', name: '测试账套', accountingStandardCode: 'CAS', accountingStandardVersion: '2011-17', baseCurrency: 'CNY', startDate: '2026-01-01', approvalEnabled: false, status: 'ACTIVE', description: '' }
      if (path === '/ledgers/ledger-1/periods') return [{ id: 'period-8', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: true }]
      if (path.includes('/reports/statutory/cash-flow')) {
        throw new ApiError(422, { code: 'STATUTORY_REPORT_UNSUPPORTED_STANDARD', title: '法定报表不可用', detail: '当前账套不是小企业会计准则，暂不提供法定报表' })
      }
      throw new Error(`unexpected call ${path}`)
    })
    renderPage()

    expect((await screen.findAllByText(/当前账套不是小企业会计准则/)).length).toBeGreaterThan(0)
    const calls = (apiFetch as unknown as ReturnType<typeof vi.fn>).mock.calls.map((call) => String(call[0]))
    expect(calls.some((call) => call.includes('/reports/income-statement'))).toBe(false)
    expect(calls.some((call) => call.includes('/reports/balance-sheet'))).toBe(false)
  })

  it('shows the projection-pending hint when the backend returns 409', async () => {
    installBackend({ accountingStandardCode: 'SME', baseCurrency: 'CNY' }, () => {
      throw new ApiError(409, { code: 'STATUTORY_REPORT_PROJECTION_PENDING', title: '法定报表暂不可用', detail: '余额投影正在更新，请稍后刷新报表' })
    })
    renderPage()
    expect((await screen.findAllByText(/余额投影正在更新，请稍后刷新报表/)).length).toBeGreaterThan(0)
  })

  it('shows the formula-missing error when no published CASH_FLOW formula exists', async () => {
    installBackend({ accountingStandardCode: 'SME', baseCurrency: 'CNY' }, () => {
      throw new ApiError(500, { code: 'STATUTORY_FORMULA_NOT_FOUND', title: '法定报表公式缺失', detail: '当前账套缺少已发布的报表公式' })
    })
    renderPage()
    expect((await screen.findAllByText(/当前账套缺少已发布的报表公式/)).length).toBeGreaterThan(0)
    expect(screen.queryByLabelText('正在生成法定报表')).not.toBeInTheDocument()
  })

  it('shows an explicit empty state when the ledger has no accounting periods', async () => {
    const backend = installBackend(
      { accountingStandardCode: 'SME', baseCurrency: 'CNY' },
      undefined,
      [],
    )
    renderPage()

    expect(await screen.findByText('尚未设置会计期间')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '设置会计期间' })).toBeInTheDocument()
    expect(screen.queryByLabelText('正在生成法定报表')).not.toBeInTheDocument()
    expect(backend.mock.calls.some(([path]) => String(path).includes('/reports/statutory/'))).toBe(false)
  })
})
