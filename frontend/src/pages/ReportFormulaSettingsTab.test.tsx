import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'
import { ReportFormulaSettingsTab } from './ReportFormulaSettingsTab'

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

const publishedDefinition = {
  schemaVersion: 1,
  kind: 'FIXED_LINES',
  reportType: 'BALANCE_SHEET',
  templateCode: 'SME-2011-17',
  columnPolicy: { primary: 'CLOSING', comparative: 'OPENING' },
  groups: [{
    key: 'LEFT',
    title: '资产',
    lines: [
      { key: 'bs-0', lineNo: 0, indent: 0, rowType: 'SECTION', name: '流动资产：', expression: { type: 'LINEAR_COMBINATION', components: [] } },
      { key: 'bs-1', lineNo: 1, indent: 0, rowType: 'DETAIL', name: '货币资金', expression: { type: 'ACCOUNT_AMOUNT', operation: 'ACCOUNT_BALANCE', side: 'DEBIT', accounts: [{ type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.CASH' }] } },
      { key: 'bs-2', lineNo: 2, indent: 0, rowType: 'TOTAL', name: '资产合计', expression: { type: 'LINEAR_COMBINATION', components: [{ lineKey: 'bs-1', factor: 1 }] } },
    ],
  }],
  rules: [],
  checks: [],
  debitCategories: ['CURRENT_ASSET'],
  creditCategories: [],
}

function workspace(publishedVersion = 1, draft?: { version: number; definition?: unknown; lastPreviewedDraftVersion?: number | null; previewHasWarnings?: boolean }) {
  return {
    code: 'BALANCE_SHEET',
    name: '资产负债表',
    kind: 'FIXED_LINES',
    reportType: 'BALANCE_SHEET',
    templateCode: 'SME-2011-17',
    publishedVersion,
    publishedDefinition,
    draft: draft ? {
      version: draft.version,
      basePublishedVersion: publishedVersion,
      definition: draft.definition ?? publishedDefinition,
      lastPreviewedDraftVersion: draft.lastPreviewedDraftVersion ?? null,
      previewHasWarnings: draft.previewHasWarnings ?? false,
      updatedAt: '2026-01-01T00:00:00Z',
    } : null,
  }
}

function installBackend(role = 'OWNER') {
  let draft: { version: number; definition: unknown; lastPreviewedDraftVersion: number | null; previewHasWarnings: boolean } | null = null
  let publishedVersion = 1
  const mock = vi.fn(async (path: string, _auth: unknown, init?: RequestInit) => {
    if (path === '/ledgers/ledger-1/accounts') return [{ id: 'a1', code: '1001', name: '库存现金', standardAccountKey: 'ASSET.CASH', category: 'CURRENT_ASSET', normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 1, isLeaf: true }]
    if (path === '/ledgers/ledger-1/periods') return [{ id: 'p1', periodCode: '2026-01', startDate: '2026-01-01', endDate: '2026-01-31', status: 'OPEN' }]
    if (path === '/ledgers/ledger-1/role') return { role }
    if (path === '/ledgers/ledger-1/report-formulas/BALANCE_SHEET') {
      return workspace(publishedVersion, draft ?? undefined)
    }
    if (path === '/ledgers/ledger-1/report-formulas/BALANCE_SHEET/draft' && !draft) {
      draft = { version: 1, definition: publishedDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false }
      return { version: 1, basePublishedVersion: publishedVersion, definition: publishedDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false, updatedAt: '2026-01-01T00:00:00Z' }
    }
    if (path.startsWith('/ledgers/ledger-1/report-formulas/BALANCE_SHEET/draft') && draft) {
      const body = JSON.parse(String(mock.mock.calls.at(-1)?.[2]?.body || '{}')) as { lines?: { lineKey: string; name: string }[] }
      if (path.endsWith(':preview')) {
        draft.lastPreviewedDraftVersion = draft.version
        return { draftVersion: draft.version, previewedDraftVersion: draft.version, previewHasWarnings: false, blockingIssues: [], warnings: [], statement: { totalLines: 1, lines: [{ code: '1001', name: '货币资金', amount: '100.00' }] } }
      }
      if (path.endsWith(':reset')) {
        draft = { version: draft.version + 1, definition: publishedDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false }
        return { version: draft.version, basePublishedVersion: publishedVersion, definition: publishedDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false, updatedAt: '2026-01-01T00:00:00Z' }
      }
      // PUT draft: merge name edits into the definition.
      const definition = JSON.parse(JSON.stringify(draft.definition)) as typeof publishedDefinition
      const edits = new Map((body.lines || []).map((line) => [line.lineKey, line.name]))
      definition.groups = definition.groups.map((group) => ({ ...group, lines: group.lines.map((line) => edits.has(line.key) ? { ...line, name: edits.get(line.key) ?? line.name } : line) }))
      draft = { version: draft.version + 1, definition, lastPreviewedDraftVersion: null, previewHasWarnings: false }
      return { version: draft.version, basePublishedVersion: publishedVersion, definition, lastPreviewedDraftVersion: null, previewHasWarnings: false, updatedAt: '2026-01-01T00:00:00Z' }
    }
    if (path.endsWith(':publish')) {
      publishedVersion += 1
      draft = null
      return { formulaCode: 'BALANCE_SHEET', publishedVersion }
    }
    if (path.endsWith('/draft') ) {
      draft = null
      return undefined
    }
    return undefined
  })
  ;(apiFetch as unknown as ReturnType<typeof vi.fn>).mockImplementation(mock)
  return { mock, getDraft: () => draft }
}

function renderTab() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/ledgers/ledger-1/settings/report-formulas']}>
          <Routes><Route path="/ledgers/:ledgerId/settings/report-formulas" element={<ReportFormulaSettingsTab />} /></Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
  return client
}

describe('ReportFormulaSettingsTab', () => {
  it('runs create → edit → save → preview → publish for an owner', async () => {
    const backend = installBackend('OWNER')
    renderTab()

    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))

    await waitFor(() => expect(screen.getByRole('button', { name: '保存草稿' })).toBeInTheDocument())
    const nameInput = screen.getByLabelText('第 1 行项目名称')
    fireEvent.change(nameInput, { target: { value: '货币资金（测试）' } })
    await waitFor(() => expect(screen.getByText('有未保存修改')).toBeInTheDocument())
    expect((screen.getByRole('button', { name: /^试\s*算$/ }) as HTMLButtonElement).disabled).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(backend.getDraft()?.version).toBe(2))
    await waitFor(() => expect((screen.getByRole('button', { name: /^试\s*算$/ }) as HTMLButtonElement).disabled).toBe(false))

    fireEvent.click(screen.getByRole('button', { name: /^试\s*算$/ }))
    await waitFor(() => expect(screen.getByText('试算通过')).toBeInTheDocument())
    await waitFor(() => expect((screen.getByRole('button', { name: /^发\s*布$/ }) as HTMLButtonElement).disabled).toBe(false))

    fireEvent.change(nameInput, { target: { value: '货币资金（再次修改）' } })
    fireEvent.click(screen.getByRole('button', { name: '保存草稿' }))
    await waitFor(() => expect(backend.getDraft()?.version).toBe(3))
    await waitFor(() => expect((screen.getByRole('button', { name: /^试\s*算$/ }) as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: /^试\s*算$/ }))
    await waitFor(() => expect(backend.getDraft()?.lastPreviewedDraftVersion).toBe(3))

    fireEvent.click(screen.getByRole('button', { name: /^发\s*布$/ }))
    await waitFor(() => expect(backend.getDraft()).toBeNull())
    await waitFor(() => expect(screen.getByText('当前发布版本 v2')).toBeInTheDocument())
  })

  it('keeps viewers fully read-only', async () => {
    installBackend('VIEWER')
    renderTab()

    await waitFor(() => expect(screen.getByText('当前发布版本 v1')).toBeInTheDocument())
    const createButton = screen.getByRole('button', { name: '创建草稿' }) as HTMLButtonElement
    expect(createButton.disabled).toBe(true)
    expect((screen.getByLabelText('第 1 行项目名称') as HTMLInputElement).disabled).toBe(true)
  })

  it('shows the conflict dialog instead of silently overwriting', async () => {
    installBackend('OWNER')
    renderTab()

    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '保存草稿' })).toBeInTheDocument())

    fireEvent.change(screen.getByLabelText('第 1 行项目名称'), { target: { value: '并发修改' } })
    await waitFor(() => expect(screen.getByText('有未保存修改')).toBeInTheDocument())
    const save = screen.getByRole('button', { name: '保存草稿' })
    ;(apiFetch as unknown as ReturnType<typeof vi.fn>).mockRejectedValueOnce(
      new (await import('../api/client')).ApiError(409, { code: 'REPORT_FORMULA_VERSION_CONFLICT', detail: 'conflict' }))
    fireEvent.click(save)

    await waitFor(() => expect(screen.getAllByText('版本冲突').length).toBeGreaterThan(0))
    expect(screen.getByText(/刷新将丢弃本地的未保存修改/)).toBeInTheDocument()
  })
})

const cashFlowDefinition = {
  schemaVersion: 1,
  kind: 'FIXED_LINES',
  reportType: 'CASH_FLOW',
  templateCode: 'SME-2011-17-CASH-FLOW',
  columnPolicy: { primary: 'ACTIVITY', comparative: 'ACTIVITY' },
  groups: [{
    key: 'OPERATING',
    title: '一、经营活动产生的现金流量',
    lines: [
      { key: 'cf-1', lineNo: 1, indent: 0, rowType: 'DETAIL', name: '销售产成品、商品、提供劳务收到的现金', expression: { type: 'CASH_FLOW_ITEM_AMOUNT', direction: 'INFLOW', itemCodes: ['SME_CF_01_SALES_RECEIPTS'], cashAccounts: [{ type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.CASH' }, { type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.BANK_DEPOSIT' }] } },
      { key: 'cf-7', lineNo: 7, indent: 0, rowType: 'TOTAL', name: '经营活动产生的现金流量净额', expression: { type: 'LINEAR_COMBINATION', components: [{ lineKey: 'cf-1', factor: 1 }] } },
    ],
  }, {
    key: 'BALANCES',
    title: '四、现金净增加额及现金余额',
    lines: [
      { key: 'cf-21', lineNo: 21, indent: 0, rowType: 'DETAIL', name: '加：期初现金余额', expression: { type: 'ACCOUNT_AMOUNT', operation: 'ACCOUNT_BALANCE', side: 'DEBIT', basis: 'OPENING', accounts: [{ type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.CASH' }] } },
      { key: 'cf-22', lineNo: 22, indent: 0, rowType: 'TOTAL', name: '五、期末现金余额', expression: { type: 'ACCOUNT_AMOUNT', operation: 'ACCOUNT_BALANCE', side: 'DEBIT', basis: 'CLOSING', accounts: [{ type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.CASH' }] } },
    ],
  }],
  rules: [],
  checks: [],
}

describe('ReportFormulaSettingsTab cash-flow', () => {
  const cashFlowItems = [
    { id: 'item-sales', ledgerId: 'ledger-1', code: 'SME_CF_01_SALES_RECEIPTS', name: '销售收到的现金', status: 'ACTIVE', template: true },
    { id: 'item-tax', ledgerId: 'ledger-1', code: 'SME_CF_05_TAX_PAYMENTS', name: '支付的税费', status: 'ACTIVE', template: true },
  ]

  function installCashFlowBackend() {
    const state: { draft: { version: number; definition: unknown; lastPreviewedDraftVersion: number | null; previewHasWarnings: boolean } | null; publishedVersion: number } = {
      draft: null,
      publishedVersion: 1,
    }
    // Stable workspace reference so refetches after preview do not reset the preview pane.
    const workspaceData: Record<string, unknown> = {
      code: 'CASH_FLOW', name: '现金流量表', kind: 'FIXED_LINES', reportType: 'CASH_FLOW',
      templateCode: 'SME-2011-17-CASH-FLOW', publishedVersion: state.publishedVersion,
      publishedDefinition: cashFlowDefinition, draft: null,
    }
    const syncDraft = () => {
      workspaceData.draft = state.draft ? {
        version: state.draft.version, basePublishedVersion: 1, definition: state.draft.definition,
        lastPreviewedDraftVersion: state.draft.lastPreviewedDraftVersion, previewHasWarnings: state.draft.previewHasWarnings,
        updatedAt: '2026-01-01T00:00:00Z',
      } : null
    }
    const mock = vi.fn(async (path: string, _auth: unknown, init?: RequestInit) => {
      if (path === '/ledgers/ledger-1/accounts') return [{ id: 'a1', code: '1001', name: '库存现金', standardAccountKey: 'ASSET.CASH', category: 'CURRENT_ASSET', normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 1, isLeaf: true, cashFlowRequired: true, defaultCashFlowItemId: 'item-sales' }]
      if (path === '/ledgers/ledger-1/periods') return [{ id: 'p1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN' }]
      if (path === '/ledgers/ledger-1/role') return { role: 'OWNER' }
      if (path === '/ledgers/ledger-1/cash-flow-items') return cashFlowItems
      if (path === '/ledgers/ledger-1/report-formulas/CASH_FLOW') {
        syncDraft()
        return workspaceData
      }
      if (path === '/ledgers/ledger-1/report-formulas/CASH_FLOW/draft' && !state.draft) {
        state.draft = { version: 1, definition: cashFlowDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false }
        return { version: 1, basePublishedVersion: 1, definition: cashFlowDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false, updatedAt: '2026-01-01T00:00:00Z' }
      }
      if (path.startsWith('/ledgers/ledger-1/report-formulas/CASH_FLOW/draft') && state.draft) {
        if (path.endsWith(':preview')) {
          state.draft.lastPreviewedDraftVersion = state.draft.version
          return {
            draftVersion: state.draft.version, previewedDraftVersion: state.draft.version, previewHasWarnings: false,
            blockingIssues: [], warnings: [],
            statement: {
              reportType: 'cash-flow', templateCode: 'SME-2011-17-CASH-FLOW', standardCode: 'SME',
              standardVersion: '2011-17', periodCode: '2026-08', primaryColumn: '本年累计金额', comparativeColumn: '本月金额',
              groups: [{ key: 'OPERATING', title: '一、经营活动产生的现金流量', lines: [{ key: 'cf-1', lineNo: 1, indent: 0, rowType: 'DETAIL', name: '销售产成品、商品、提供劳务收到的现金', primaryAmount: '100.00', comparativeAmount: '10.00' }] }],
              checks: [],
              formulaCode: 'CASH_FLOW', formulaVersion: 1,
              dataQuality: { status: 'INCOMPLETE', primaryUnclassifiedVoucherCount: 1, primaryUnclassifiedLineCount: 1, comparativeUnclassifiedVoucherCount: 0, comparativeUnclassifiedLineCount: 0, samples: [] },
            },
          }
        }
        if (path.endsWith(':reset')) {
          state.draft = { version: state.draft.version + 1, definition: cashFlowDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false }
          return { version: state.draft.version, basePublishedVersion: 1, definition: cashFlowDefinition, lastPreviewedDraftVersion: null, previewHasWarnings: false, updatedAt: '2026-01-01T00:00:00Z' }
        }
        if (path.endsWith('/draft') && init?.method === 'DELETE') {
          state.draft = null
          return undefined
        }
        return undefined
      }
      if (path.endsWith(':publish')) {
        state.publishedVersion += 1
        state.draft = null
        return { formulaCode: 'CASH_FLOW', publishedVersion: state.publishedVersion }
      }
      return undefined
    })
    ;(apiFetch as unknown as ReturnType<typeof vi.fn>).mockImplementation(mock)
    return mock
  }

  function renderCashFlowTab(initial = '/ledgers/ledger-1/settings/report-formulas?formula=CASH_FLOW') {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={client}>
        <App>
          <MemoryRouter initialEntries={[initial]}>
            <Routes><Route path="/ledgers/:ledgerId/settings/report-formulas" element={<ReportFormulaSettingsTab />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )
    return client
  }

  it('preselects CASH_FLOW from the formula query parameter', async () => {
    installCashFlowBackend()
    renderCashFlowTab()

    await waitFor(() => expect(screen.getByText('当前发布版本 v1')).toBeInTheDocument())
    const item = screen.getByText('现金流量表').closest('.ant-segmented-item')
    expect(item).not.toBeNull()
    expect(item!.className).toContain('ant-segmented-item-selected')
  })

  it('switches the formula type to 现金流量表 and loads the cash-flow workspace', async () => {
    installCashFlowBackend()
    renderCashFlowTab('/ledgers/ledger-1/settings/report-formulas')

    await waitFor(() => expect(screen.getByText('现金流量表').closest('.ant-segmented-item')).not.toBeNull())
    fireEvent.click(screen.getByText('现金流量表').closest('.ant-segmented-item')!)
    await waitFor(() => expect(screen.getByText('当前发布版本 v1')).toBeInTheDocument())
    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    expect((screen.getByLabelText('第 1 行项目名称') as HTMLInputElement).value).toBe('销售产成品、商品、提供劳务收到的现金')
  })

  it('edits a cash-flow line expression with direction, item and cash account selectors', async () => {
    installCashFlowBackend()
    renderCashFlowTab()

    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '保存草稿' })).toBeInTheDocument())

    const direction = screen.getByRole('combobox', { name: '现金流方向' })
    expect(screen.getByText('流入')).toBeInTheDocument()
    fireEvent.mouseDown(direction)
    await screen.findByText('流出')
    const openDropdowns = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'))
    fireEvent.click(within(openDropdowns.at(-1)!).getByText('流出'))
    await waitFor(() => expect(screen.getByText('有未保存修改')).toBeInTheDocument())

    const itemSelect = screen.getByRole('combobox', { name: '现金流项目' })
    expect(itemSelect).toBeInTheDocument()
  })

  it('keeps the OPENING/CLOSING balance basis visible on the balance rows', async () => {
    installCashFlowBackend()
    renderCashFlowTab()

    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))
    await waitFor(() => expect(screen.getByRole('button', { name: '保存草稿' })).toBeInTheDocument())

    const basisSelects = screen.getAllByRole('combobox', { name: '余额基准' })
    expect(basisSelects).toHaveLength(2)
    expect(screen.getByText('期初余额')).toBeInTheDocument()
    expect(screen.getByText('期末余额')).toBeInTheDocument()
  })

  it('shows the continuous statement and data-quality alert in the cash-flow preview', async () => {
    installCashFlowBackend()
    renderCashFlowTab()

    await waitFor(() => expect(screen.getByRole('button', { name: '创建草稿' })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '创建草稿' }))
    await waitFor(() => expect(screen.getByRole('button', { name: /^试\s*算$/ })).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /^试\s*算$/ }))

    await waitFor(() => expect(screen.getByText('存在未分类的现金收支')).toBeInTheDocument())
    expect(screen.getByText(/本年累计：\s*1 张凭证 \/ 1 行；\s*本月：\s*0 张凭证 \/ 0 行/)).toBeInTheDocument()
    expect(document.querySelector('.cash-flow-statement-table')).not.toBeNull()
    expect(screen.getByText('销售产成品、商品、提供劳务收到的现金')).toBeInTheDocument()
  })
})
