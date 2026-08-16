import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
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
