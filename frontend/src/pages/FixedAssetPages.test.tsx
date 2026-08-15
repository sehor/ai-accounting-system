import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, openApiClient } from '../api/client'
import { departmentNameById, FixedAssetEditorPage, fixedAssetPayload, formatFixedAssetMoney, normalizeFixedAssetTab } from './FixedAssetPages'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn(), openApiClient: { GET: vi.fn(), POST: vi.fn(), PATCH: vi.fn(), DELETE: vi.fn() } }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

const period = { id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN' as const }
const account = {
  id: 'account-1', ledgerId: 'ledger-1', code: '1601', name: '固定资产', category: 'NON_CURRENT_ASSET',
  normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 1, isLeaf: true, isTemplate: false,
  hasBusinessUsage: false, coreLocked: false, legacyCode: false, version: 1, cashFlowRequired: false,
  defaultCashFlowItemId: null, quantityEnabled: false, unitName: null, dimensionRequirements: [], createdAt: null,
}
const category = {
  id: 'category-1', ledgerId: 'ledger-1', code: 'OFFICE', name: '办公设备', usefulLifeMonths: 60,
  residualRate: '5', status: 'ACTIVE', assetAccountId: account.id, accumulatedDepreciationAccountId: account.id,
  depreciationExpenseAccountId: account.id, impairmentAccountId: null, clearingAccountId: account.id,
  disposalGainAccountId: account.id, disposalLossAccountId: account.id, version: 1,
}
const asset = {
  id: 'asset-1', ledgerId: 'ledger-1', categoryId: category.id, categoryCode: category.code, categoryName: category.name,
  code: 'FA-001', name: '测试电脑', status: 'ACTIVE', quantity: 1, serviceDate: '2026-08-01', originalCost: '6000',
  inputTax: '0', usefulLifeMonths: 60, residualRate: '5', residualAmount: '300', openingAccumulatedDepreciation: '100',
  openingDepreciatedMonths: 1, impairmentAmount: '0', currentDepreciation: '95', currentAccumulatedDepreciation: '95',
  endingAccumulatedDepreciation: '195', openingNetValue: '5900', endingNetValue: '5805', departmentValueId: 'dept-1',
  acquisitionVoucherId: null, assetAccountId: account.id, accumulatedDepreciationAccountId: account.id,
  depreciationExpenseAccountId: account.id, impairmentAccountId: null, clearingAccountId: account.id,
  disposalGainAccountId: account.id, disposalLossAccountId: account.id, disposalDate: null, note: '原备注', version: 2,
}

beforeAll(() => {
  window.matchMedia = vi.fn().mockImplementation((query: string) => ({
    matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  }))
})

function defaultApiResponse(path: string, init?: RequestInit) {
  if (init?.method === 'PATCH') return Promise.resolve(asset)
  if (path.endsWith('/periods')) return Promise.resolve([period])
  if (path.endsWith('/fixed-asset-categories')) return Promise.resolve([category])
  if (path.endsWith('/dimension-types')) return Promise.resolve([{ id: 'dimension-1', ledgerId: 'ledger-1', code: 'DEPARTMENT', name: '部门', required: false, status: 'ACTIVE' }])
  if (path.endsWith('/dimension-types/dimension-1/values')) return Promise.resolve([{ id: 'dept-1', ledgerId: 'ledger-1', dimensionTypeId: 'dimension-1', code: 'RD', name: '研发部', status: 'ACTIVE' }])
  if (path.endsWith('/accounts')) return Promise.resolve([account])
  if (path.includes('/fixed-assets/asset-1?')) return Promise.resolve(asset)
  return Promise.resolve([])
}

function legacyPath(path: string, options: { params?: { path?: Record<string, string>; query?: Record<string, unknown> } }) {
  let result = path.replace(/^\/v1/, '')
  for (const [name, value] of Object.entries(options.params?.path || {})) result = result.replace(`{${name}}`, value)
  if (options.params?.query?.periodId && path.endsWith('/{assetId}')) result += `?periodId=${options.params.query.periodId}`
  return result
}

async function openApiResult(method: string, path: string, options: { body?: unknown; params?: { path?: Record<string, string>; query?: Record<string, unknown> } }) {
  const init = method === 'GET' ? undefined : { method, body: options.body === undefined ? undefined : JSON.stringify(options.body) }
  const data = await apiFetch(legacyPath(path, options), { localUserId: 'user-1', localUserName: 'admin' }, init)
  return { data, response: new Response(null, { status: 200 }) }
}

const getMock = openApiClient.GET as unknown as ReturnType<typeof vi.fn>
const postMock = openApiClient.POST as unknown as ReturnType<typeof vi.fn>
const patchMock = openApiClient.PATCH as unknown as ReturnType<typeof vi.fn>
const deleteMock = openApiClient.DELETE as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(apiFetch).mockImplementation((path, _session, init) => defaultApiResponse(path, init))
  getMock.mockImplementation((path: string, options: never) => openApiResult('GET', path, options))
  postMock.mockImplementation((path: string, options: never) => openApiResult('POST', path, options))
  patchMock.mockImplementation((path: string, options: never) => openApiResult('PATCH', path, options))
  deleteMock.mockImplementation((path: string, options: never) => openApiResult('DELETE', path, options))
})

afterEach(cleanup)

function EditorHarness() {
  const navigate = useNavigate()
  return <><button onClick={() => navigate('/ledgers/ledger-1/fixed-assets/asset-1')}>测试返回资产</button><Routes>
    <Route path="/ledgers/:ledgerId/fixed-assets/new" element={<FixedAssetEditorPage />} />
    <Route path="/ledgers/:ledgerId/fixed-assets/:assetId" element={<FixedAssetEditorPage />} />
  </Routes></>
}

function renderEditor(path = '/ledgers/ledger-1/fixed-assets/asset-1') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })
  render(<QueryClientProvider client={client}><App><MemoryRouter initialEntries={[path]}><EditorHarness /></MemoryRouter></App></QueryClientProvider>)
}

describe('fixed asset presentation', () => {
  it('formats depreciation amounts with two decimals', () => {
    expect(formatFixedAssetMoney('1234.5')).toBe('1,234.50')
    expect(formatFixedAssetMoney(null)).toBe('-')
  })

  it('maps a department value id to its display name and falls back from invalid tabs', () => {
    const departments = departmentNameById([{
      id: 'dept-1', ledgerId: 'ledger-1', dimensionTypeId: 'type-1', code: 'RD', name: '研发部', status: 'ACTIVE', version: 0,
    }])

    expect(departments.get('dept-1')).toBe('研发部')
    expect(normalizeFixedAssetTab('depreciation')).toBe('cards')
    expect(normalizeFixedAssetTab('unexpected')).toBe('cards')
    expect(normalizeFixedAssetTab('categories')).toBe('categories')
  })

  it('omits server-owned and immutable fields from an edit patch', () => {
    const payload = fixedAssetPayload({
      ...asset, serviceDate: '2026-08-01', name: '更新名称', categoryId: 'category-2', code: 'CHANGED',
      changePeriodId: period.id,
    }, true)

    expect(payload).toMatchObject({ name: '更新名称', serviceDate: '2026-08-01' })
    expect(payload).not.toHaveProperty('categoryId')
    expect(payload).not.toHaveProperty('code')
    expect(payload).not.toHaveProperty('openingAccumulatedDepreciation')
    expect(payload).not.toHaveProperty('openingDepreciatedMonths')
    expect(payload).not.toHaveProperty('currentDepreciation')
    expect(payload).not.toHaveProperty('currentAccumulatedDepreciation')
    expect(payload).toHaveProperty('changePeriodId', period.id)
  })
})

describe('FixedAssetEditorPage', () => {
  it('submits an edit PATCH without immutable fields', async () => {
    renderEditor()
    expect(await screen.findByDisplayValue('测试电脑')).toBeInTheDocument()
    const saveButton = screen.getByRole('button', { name: /保\s*存/ })
    await waitFor(() => expect(saveButton).toBeEnabled())
    fireEvent.click(saveButton)

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/fixed-assets/asset-1',
      { localUserId: 'user-1', localUserName: 'admin' },
      expect.objectContaining({ method: 'PATCH' }),
    ))
    const patchCall = vi.mocked(apiFetch).mock.calls.find(([path, , init]) => path.endsWith('/fixed-assets/asset-1') && init?.method === 'PATCH')!
    const payload = JSON.parse(patchCall[2]!.body as string)
    expect(payload).not.toHaveProperty('categoryId')
    expect(payload).not.toHaveProperty('code')
    expect(payload).not.toHaveProperty('openingAccumulatedDepreciation')
    expect(payload).not.toHaveProperty('openingDepreciatedMonths')
    expect(payload).not.toHaveProperty('currentDepreciation')
    expect(payload).not.toHaveProperty('currentAccumulatedDepreciation')
  })

  it('resets both editor state and cleanup form when entering the new route', async () => {
    renderEditor()
    expect(await screen.findByDisplayValue('测试电脑')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /清\s*理/ }))
    fireEvent.change(await screen.findByLabelText('清理原因'), { target: { value: '待重置原因' } })
    fireEvent.click(within(screen.getByRole('dialog', { name: '资产清理向导' })).getByRole('button', { name: 'Cancel' }))
    fireEvent.click(screen.getByRole('button', { name: /新\s*增/ }))

    expect(await screen.findByRole('heading', { name: '新增固定资产' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '资产名称' })).toHaveValue('')
    expect(screen.queryByRole('button', { name: /新\s*增/ })).not.toBeInTheDocument()

    fireEvent.click(screen.getByRole('button', { name: '测试返回资产' }))
    await screen.findByDisplayValue('测试电脑')
    fireEvent.click(screen.getByRole('button', { name: /清\s*理/ }))
    expect(await screen.findByLabelText('清理原因')).toHaveValue('')
  }, 15_000)

  it('keeps copy available for a disposed asset while locking destructive edits', async () => {
    vi.mocked(apiFetch).mockImplementation((path, _session, init) => {
      if (path.includes('/fixed-assets/asset-1?')) return Promise.resolve({ ...asset, status: 'DISPOSED', disposalDate: '2026-08-10' })
      return defaultApiResponse(path, init)
    })
    renderEditor()

    expect(await screen.findByText('已清理')).toBeInTheDocument()
    const copyButton = screen.getByRole('button', { name: /复\s*制/ })
    await waitFor(() => expect(copyButton).toBeEnabled())
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /清\s*理/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /删\s*除/ })).not.toBeInTheDocument()
  })

  it('shows a reference error and disables changes when account loading fails', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.endsWith('/accounts')) return Promise.reject(new Error('accounts unavailable'))
      if (path.endsWith('/periods')) return Promise.resolve([period])
      if (path.endsWith('/fixed-asset-categories')) return Promise.resolve([category])
      if (path.endsWith('/dimension-types')) return Promise.resolve([{ id: 'dimension-1', ledgerId: 'ledger-1', code: 'DEPARTMENT', name: '部门', required: false, status: 'ACTIVE' }])
      if (path.endsWith('/dimension-types/dimension-1/values')) return Promise.resolve([])
      return Promise.resolve([])
    })
    renderEditor('/ledgers/ledger-1/fixed-assets/new')

    expect(await screen.findByText('资产卡片依赖数据加载失败')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /保\s*存/ })).toBeDisabled()
    expect(screen.getByRole('textbox', { name: '资产名称' })).toBeDisabled()
    expect(screen.queryByRole('button', { name: /新\s*增/ })).not.toBeInTheDocument()
  })
})
