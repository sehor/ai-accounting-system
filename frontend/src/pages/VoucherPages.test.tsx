import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, apiFetchWithHeaders, openApiClient, ApiError } from '../api/client'
import { WorkspaceTabsProvider } from '../components/workspaceTabs'
import { buildVoucherRequestBody, dateBelongsToPeriod, openPeriodForDate, validateCashFlowLines, voucherAmountPattern, VoucherEditorPage, VoucherListPage } from './VoucherPages'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return {
    ...actual,
    apiFetch: vi.fn(),
    apiFetchWithHeaders: vi.fn(),
    openApiClient: { GET: vi.fn(), POST: vi.fn(), PUT: vi.fn(), DELETE: vi.fn() },
  }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

const CurrentPath = () => <output aria-label="当前路径">{useLocation().pathname}</output>
const openApiResult = (data: unknown, status = 200) => Promise.resolve({ data, response: new Response(null, { status }) })
const mockedOpenApiMethod = (method: keyof typeof openApiClient) => openApiClient[method] as unknown as ReturnType<typeof vi.fn>

beforeAll(() => {
  const getComputedStyle = window.getComputedStyle
  window.matchMedia = (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: vi.fn(),
    removeListener: vi.fn(),
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
    dispatchEvent: vi.fn(),
  })
  window.getComputedStyle = ((element: Element) => getComputedStyle(element)) as typeof window.getComputedStyle
  Object.defineProperty(URL, 'createObjectURL', { writable: true, value: () => '' })
  Object.defineProperty(URL, 'revokeObjectURL', { writable: true, value: () => {} })
})

beforeEach(() => {
  vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(
    path.includes('kingdee:export') ? new Blob(['xlsx'])
      : path.endsWith('/periods') ? [{
          id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01',
          endDate: '2026-06-30', status: 'OPEN', hasVouchers: true,
        }]
        : [],
  ))
  vi.mocked(apiFetchWithHeaders).mockResolvedValue({ data: [], headers: new Headers({ 'X-Total-Count': '0' }) })
  mockedOpenApiMethod('GET').mockImplementation(async (path: string, options: { params?: { path?: Record<string, string>; query?: Record<string, unknown> } }) => {
    const pathParameters = options.params?.path || {}
    const query = new URLSearchParams(Object.entries(options.params?.query || {})
      .filter(([, value]) => value !== undefined && value !== '')
      .map(([key, value]) => [key, String(value)]))
    const legacyPath = path
      .replace('/v1', '')
      .replace('{ledgerId}', pathParameters.ledgerId || '')
      .replace('{voucherId}', pathParameters.voucherId || '')
      .replace('{code}', pathParameters.code || '')
      + (query.size ? `?${query}` : '')
    if (path.endsWith('/vouchers')) {
      const response = await apiFetchWithHeaders(legacyPath, { localUserId: 'user-1', localUserName: 'admin' })
      return { data: response.data, response: new Response(null, { status: 200, headers: response.headers }) }
    }
    return openApiResult(await apiFetch(legacyPath, { localUserId: 'user-1', localUserName: 'admin' }))
  })
  mockedOpenApiMethod('POST').mockImplementation(async (path: string, options: { params?: { path?: Record<string, string> }; body?: Record<string, unknown>; headers?: HeadersInit }) => {
    const ledgerId = options.params?.path?.ledgerId || ''
    if (path.endsWith('/dimension-values:batch')) {
      const typeIds = options.body?.dimensionTypeIds as string[]
      const groups = await Promise.all(typeIds.map(async (dimensionTypeId) => ({
        dimensionTypeId,
        values: await apiFetch(`/ledgers/${ledgerId}/dimension-types/${dimensionTypeId}/values`, { localUserId: 'user-1', localUserName: 'admin' }),
      })))
      return openApiResult({ groups })
    }
    const voucherId = options.params?.path?.voucherId
    const legacyPath = path.replace('/v1', '').replace('{ledgerId}', ledgerId).replace('{voucherId}', voucherId || '')
    return openApiResult(await apiFetch(legacyPath, { localUserId: 'user-1', localUserName: 'admin' }, {
      method: 'POST', headers: options.headers, body: options.body ? JSON.stringify(options.body) : undefined,
    }))
  })
  mockedOpenApiMethod('PUT').mockImplementation(async (path: string, options: { params?: { path?: Record<string, string> }; body?: Record<string, unknown> }) => {
    const pathParameters = options.params?.path || {}
    const legacyPath = path.replace('/v1', '').replace('{ledgerId}', pathParameters.ledgerId || '').replace('{voucherId}', pathParameters.voucherId || '')
    return openApiResult(await apiFetch(legacyPath, { localUserId: 'user-1', localUserName: 'admin' }, { method: 'PUT', body: JSON.stringify(options.body) }))
  })
  mockedOpenApiMethod('DELETE').mockImplementation(async (path: string, options: { params?: { path?: Record<string, string> } }) => {
    const pathParameters = options.params?.path || {}
    const legacyPath = path.replace('/v1', '').replace('{ledgerId}', pathParameters.ledgerId || '').replace('{voucherId}', pathParameters.voucherId || '')
    await apiFetch(legacyPath, { localUserId: 'user-1', localUserName: 'admin' }, { method: 'DELETE' })
    return openApiResult(undefined, 204)
  })
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('VoucherListPage', () => {
  it('intersects detailed date filters with the selected accounting period range', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers?startDate=2026-06-01&endDate=2026-07-31&keyword=工资']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(apiFetchWithHeaders).toHaveBeenCalled())
    const path = vi.mocked(apiFetchWithHeaders).mock.calls
      .map(([requestPath]) => requestPath).find((requestPath) => requestPath.includes('/vouchers?'))!
    const params = new URL(path, 'http://localhost').searchParams
    expect(params.get('startDate')).toBe('2026-06-01')
    expect(params.get('endDate')).toBe('2026-06-30')
    expect(params.get('keyword')).toBe('工资')
    expect(params.get('periodCode')).toBeNull()
  })

  it('queries and shows vouchers across the selected accounting period range', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(
      path.endsWith('/periods') ? [
        { id: 'period-6', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN', hasVouchers: true },
        { id: 'period-7', ledgerId: 'ledger-1', periodCode: '2026-07', startDate: '2026-07-01', endDate: '2026-07-31', status: 'OPEN', hasVouchers: true },
      ] : [],
    ))
    vi.mocked(apiFetchWithHeaders).mockResolvedValue({
      data: [
        { id: 'voucher-6', ledgerId: 'ledger-1', periodId: 'period-6', voucherDate: '2026-06-02', voucherType: '记', voucherNumber: '1', summary: '六月凭证', status: 'POSTED', approvalRequired: false, version: 0, lines: [] },
        { id: 'voucher-7', ledgerId: 'ledger-1', periodId: 'period-7', voucherDate: '2026-07-02', voucherType: '记', voucherNumber: '2', summary: '七月凭证', status: 'POSTED', approvalRequired: false, version: 0, lines: [] },
      ],
      headers: new Headers({ 'X-Total-Count': '2' }),
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers?periodFrom=2026-06&periodTo=2026-07']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('六月凭证')).toBeInTheDocument()
    expect(screen.getByText('七月凭证')).toBeInTheDocument()
    const path = vi.mocked(apiFetchWithHeaders).mock.calls
      .map(([requestPath]) => requestPath).find((requestPath) => requestPath.includes('/vouchers?'))!
    const params = new URL(path, 'http://localhost').searchParams
    expect(params.get('startDate')).toBe('2026-06-01')
    expect(params.get('endDate')).toBe('2026-07-31')
    expect(params.get('periodCode')).toBeNull()
  })

  it('shows an empty state instead of vouchers from another period', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(
      path.endsWith('/periods') ? [
        { id: 'period-6', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN', hasVouchers: true },
        { id: 'period-7', ledgerId: 'ledger-1', periodCode: '2026-07', startDate: '2026-07-01', endDate: '2026-07-31', status: 'OPEN', hasVouchers: false },
        { id: 'period-8', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: false },
      ] : [],
    ))
    vi.mocked(apiFetchWithHeaders).mockResolvedValue({
      data: [{
        id: 'voucher-6', ledgerId: 'ledger-1', periodId: 'period-6', voucherDate: '2026-06-02',
        voucherType: '记', voucherNumber: '1', summary: '六月凭证', status: 'POSTED',
        approvalRequired: false, version: 0, lines: [],
      }],
      headers: new Headers({ 'X-Total-Count': '1' }),
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers?periodCode=2026-07']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('2026年第7期没有凭证数据')).toBeInTheDocument()
    expect(screen.queryByText('2026-06-02')).not.toBeInTheDocument()
    const path = vi.mocked(apiFetchWithHeaders).mock.calls
      .map(([requestPath]) => requestPath).find((requestPath) => requestPath.includes('/vouchers?'))!
    const params = new URL(path, 'http://localhost').searchParams
    expect(params.get('startDate')).toBe('2026-07-01')
    expect(params.get('endDate')).toBe('2026-07-31')
  })

  it('only offers deletion for a voucher row', async () => {
    vi.mocked(apiFetchWithHeaders).mockResolvedValueOnce({ data: [{
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-11',
      voucherType: '记', voucherNumber: '1', summary: '缴纳社保', status: 'DRAFT',
      approvalRequired: false, version: 0, lines: [],
    }], headers: new Headers({ 'X-Total-Count': '1' }) })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const closeTab = vi.fn()
    queryClient.setQueryData(['voucher', 'ledger-1', 'voucher-1'], { id: 'voucher-1' })

    render(
      <QueryClientProvider client={queryClient}>
        <WorkspaceTabsProvider value={{ closeTab }}><App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} />
            </Routes>
          </MemoryRouter>
        </App></WorkspaceTabsProvider>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('button', { name: '删除' })).toBeInTheDocument()
    expect(screen.queryByRole('link', { name: '继续处理' })).not.toBeInTheDocument()
    expect(screen.queryByText('状态')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '删除' }))
    const confirmDialog = await screen.findByRole('dialog', { name: '确认删除凭证？' })
    fireEvent.click(within(confirmDialog).getByRole('button', { name: /删\s*除/ }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/vouchers/voucher-1',
      { localUserId: 'user-1', localUserName: 'admin' },
      { method: 'DELETE' },
    ))
    expect(queryClient.getQueryData(['voucher', 'ledger-1', 'voucher-1'])).toBeUndefined()
    expect(closeTab).toHaveBeenCalledWith('voucher-voucher-1', { discardChanges: true })
  })

  it('shows voucher line subjects and supports bulk posting', async () => {
    const voucher = {
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-11',
      voucherType: '记', voucherNumber: '1', summary: '缴纳社保', status: 'VALIDATED',
      approvalRequired: false, version: 0, lines: [{
        id: 'line-1', lineNo: 1, accountId: 'account-1', side: 'DEBIT', currency: 'CNY',
        originalAmount: '100', exchangeRate: '1', baseAmount: '100', summary: '缴纳社保',
        cashFlowItemId: null, quantity: null, unitPrice: null, dimensions: [],
      }],
    }
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.endsWith(':post')) return Promise.resolve({ ...voucher, status: 'POSTED' })
      if (path.endsWith('/periods')) return Promise.resolve([{
        id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01',
        endDate: '2026-06-30', status: 'OPEN', hasVouchers: true,
      }])
      return Promise.resolve([])
    })
    vi.mocked(apiFetchWithHeaders).mockResolvedValue({ data: [voucher], headers: new Headers({ 'X-Total-Count': '1' }) })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('缴纳社保')).toBeInTheDocument()
    await screen.findByText('缴纳社保')
    fireEvent.click((await screen.findAllByRole('checkbox'))[1])
    fireEvent.click(screen.getByRole('button', { name: '批量记账' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/vouchers/voucher-1:post',
      { localUserId: 'user-1', localUserName: 'admin' },
      expect.objectContaining({ method: 'POST' }),
    ))
  })

  it('requires a comment when bulk approving submitted vouchers', async () => {
    const voucher = {
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-11',
      voucherType: '记', voucherNumber: '1', summary: '缴纳社保', status: 'SUBMITTED',
      approvalRequired: true, version: 0, lines: [],
    }
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.endsWith(':approve')) return Promise.resolve({ ...voucher, status: 'APPROVED' })
      if (path.endsWith('/periods')) return Promise.resolve([{
        id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01',
        endDate: '2026-06-30', status: 'OPEN', hasVouchers: true,
      }])
      return Promise.resolve([])
    })
    vi.mocked(apiFetchWithHeaders).mockResolvedValue({ data: [voucher], headers: new Headers({ 'X-Total-Count': '1' }) })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    await screen.findByText('缴纳社保')
    fireEvent.click((await screen.findAllByRole('checkbox'))[1])
    fireEvent.click(screen.getByRole('button', { name: '批量审核' }))
    fireEvent.change(screen.getByPlaceholderText('请输入审核意见（必填）'), { target: { value: '审核通过' } })
    fireEvent.click(screen.getByRole('button', { name: '确认审核' }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/vouchers/voucher-1:approve',
      { localUserId: 'user-1', localUserName: 'admin' },
      expect.objectContaining({ method: 'POST', body: JSON.stringify({ comment: '审核通过' }) }),
    ))
  })

  it('lets the user choose whether to merge entries before downloading', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const createObjectUrl = vi.spyOn(URL, 'createObjectURL').mockReturnValue('blob:kingdee-vouchers')
    const revokeObjectUrl = vi.spyOn(URL, 'revokeObjectURL').mockImplementation(() => {})
    let downloadedFileName = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloadedFileName = this.download
    })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers" element={<VoucherListPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    const exportButton = screen.getByRole('button', { name: /导出金蝶凭证/ })
    await waitFor(() => expect(exportButton).toBeEnabled())
    fireEvent.click(exportButton)
    expect(screen.getByText(/收款-主营、付款-日常、付款-主营、银行费用/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('checkbox', { name: '合并同类分录' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '导出金蝶凭证' }))
      .getByRole('button', { name: /^导\s*出$/ }))

    await waitFor(() => expect(downloadedFileName).toBe('kingdee-vouchers.xlsx'))
    expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/data-exchange/kingdee:export?mergeEntries=true&startDate=2026-06-01&endDate=2026-06-30',
      { localUserId: 'user-1', localUserName: 'admin' },
    )
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:kingdee-vouchers')
  })
})

describe('VoucherEditorPage', () => {
  const dimensionAccount = {
    id: 'account-dimension', ledgerId: 'ledger-1', code: '6602.01', name: '研发费用', category: 'EXPENSE',
    normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 2, isLeaf: true, isTemplate: false,
    hasBusinessUsage: false, coreLocked: false, legacyCode: false, version: 0, cashFlowRequired: false,
    defaultCashFlowItemId: null, quantityEnabled: false, unitName: null, createdAt: null,
    dimensionRequirements: [{ dimensionTypeId: 'dimension-department', code: 'DEPARTMENT', name: '部门', required: true }],
  }
  const plainAccount = {
    ...dimensionAccount,
    id: 'account-plain', code: '1002', name: '银行存款', category: 'CURRENT_ASSET',
    dimensionRequirements: [],
  }
  const mockDimensionEditorApi = () => {
    vi.mocked(apiFetch).mockImplementation((path, _session, options) => {
      if (path.endsWith('/periods')) return Promise.resolve([{
        id: 'period-open', ledgerId: 'ledger-1', periodCode: '2026-08',
        startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN',
      }])
      if (path.endsWith('/accounts')) return Promise.resolve([dimensionAccount, plainAccount])
      if (path.endsWith('/dimension-types')) return Promise.resolve([{
        id: 'dimension-department', ledgerId: 'ledger-1', code: 'DEPARTMENT', name: '部门', required: true, status: 'ACTIVE',
      }])
      if (path.endsWith('/dimension-types/dimension-department/values')) return Promise.resolve([
        { id: 'department-rd', ledgerId: 'ledger-1', dimensionTypeId: 'dimension-department', code: 'RD', name: '研发部', status: 'ACTIVE' },
        { id: 'department-old', ledgerId: 'ledger-1', dimensionTypeId: 'dimension-department', code: 'OLD', name: '停用部门', status: 'INACTIVE' },
      ])
      if (path.endsWith('/vouchers') && options?.method === 'POST') return Promise.resolve({
        id: 'voucher-created', ledgerId: 'ledger-1', periodId: 'period-open', voucherDate: '2026-08-14',
        voucherType: '记', voucherNumber: '1', summary: null, status: 'POSTED', approvalRequired: false, version: 0, lines: [],
      })
      return Promise.resolve([])
    })
  }
  const renderDimensionEditor = () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/new']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )
  }
  const chooseSelectOption = async (label: string, optionName: string) => {
    fireEvent.mouseDown(screen.getByRole('combobox', { name: label }))
    await screen.findAllByText(optionName)
    const openDropdowns = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'))
    fireEvent.click(within(openDropdowns.at(-1)!).getByText(optionName))
  }

  it('leaves the voucher number to the server for a new voucher', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/new']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { name: '记账凭证' })).toBeInTheDocument()
    expect(screen.queryByLabelText('凭证号')).not.toBeInTheDocument()
    expect(screen.queryByLabelText('凭证字')).not.toBeInTheDocument()
    expect(screen.getByText('凭证号由系统保存时自动生成')).toBeInTheDocument()
    expect(screen.queryByLabelText('会计期间')).not.toBeInTheDocument()
    expect(screen.getAllByLabelText(/分录摘要$/)).toHaveLength(5)
  })

  it('keeps blank lines in the request and leaves period assignment to the server', () => {
    const body = buildVoucherRequestBody({
      periodId: 'client-period-must-be-ignored',
      voucherDate: dayjs('2026-08-14'),
      voucherType: '记',
      lines: [
        { accountId: 'account-1', side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1' },
        { accountId: 'account-2', side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1' },
        { side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' },
        { side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' },
        { side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' },
      ],
    }, false)
    expect(body).not.toHaveProperty('periodId')
    expect(body.lines).toHaveLength(5)
    expect(body.lines.slice(0, 2).map((line: { accountId?: string }) => line.accountId)).toEqual(['account-1', 'account-2'])
    expect(body.lines.slice(2).every((line: { accountId?: string; originalAmount?: string }) => (
      line.accountId === undefined && line.originalAmount === ''
    ))).toBe(true)
  })

  it('keeps the current tag and updates the returned voucher id on later saves', async () => {
    const createdVoucher = {
      id: 'voucher-created', ledgerId: 'ledger-1', periodId: 'period-open', voucherDate: '2026-08-14',
      voucherType: '记', voucherNumber: '1', summary: null, status: 'POSTED',
      approvalRequired: false, version: 0, lines: [],
    }
    vi.mocked(apiFetch).mockImplementation((path, _session, options) => {
      if (path.endsWith('/periods')) return Promise.resolve([{
        id: 'period-open', ledgerId: 'ledger-1', periodCode: '2026-08',
        startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN',
      }])
      if (path.endsWith('/accounts')) return Promise.resolve([])
      if (path.endsWith('/vouchers') && options?.method === 'POST') return Promise.resolve(createdVoucher)
      if (path.endsWith('/vouchers/voucher-created') && options?.method === 'PUT') {
        return Promise.resolve({ ...createdVoucher, version: 1 })
      }
      return Promise.resolve([])
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/new']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<><VoucherEditorPage /><CurrentPath /></>} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    await screen.findByRole('button', { name: '保存并记账' })
    await waitFor(() => expect(queryClient.getQueryData(['periods', 'ledger-1'])).toBeDefined())
    fireEvent.click(screen.getByRole('button', { name: '保存并记账' }))
    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([path, , options]) => (
      path === '/ledgers/ledger-1/vouchers' && options?.method === 'POST'
    ))).toBe(true))
    expect(screen.getByRole('status', { name: '当前路径' })).toHaveTextContent('/ledgers/ledger-1/vouchers/new')

    expect(await screen.findByText('会计期间 2026-08（保存后不可修改）')).toBeInTheDocument()
    expect(screen.queryByLabelText('会计期间')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '保存修改' }))
    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([path, , options]) => (
      path === '/ledgers/ledger-1/vouchers/voucher-created' && options?.method === 'PUT'
    ))).toBe(true))
    const updateCall = vi.mocked(apiFetch).mock.calls.find(([path, , options]) => (
      path === '/ledgers/ledger-1/vouchers/voucher-created' && options?.method === 'PUT'
    ))!
    expect(JSON.parse(String(updateCall[2]?.body))).toEqual(expect.objectContaining({
      periodId: 'period-open', voucherNumber: '1', expectedVersion: 0,
    }))
    expect(screen.getByRole('status', { name: '当前路径' })).toHaveTextContent('/ledgers/ledger-1/vouchers/new')
  })

  it('maps voucher dates only to open accounting periods', () => {
    const periods = [
      { id: 'closed', ledgerId: 'ledger-1', periodCode: '2026-05', startDate: '2026-05-01', endDate: '2026-05-31', status: 'CLOSED' },
      { id: 'open', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN' },
    ]

    expect(openPeriodForDate(periods, dayjs('2026-06-15'))?.id).toBe('open')
    expect(openPeriodForDate(periods, dayjs('2026-05-15'))).toBeUndefined()
    expect(openPeriodForDate(periods, dayjs('2026-07-01'))).toBeUndefined()
  })

  it('keeps a saved voucher date inside its original accounting period', () => {
    const period = {
      id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06',
      startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN',
    }

    expect(dateBelongsToPeriod(period, dayjs('2026-06-01'))).toBe(true)
    expect(dateBelongsToPeriod(period, dayjs('2026-06-30'))).toBe(true)
    expect(dateBelongsToPeriod(period, dayjs('2026-07-01'))).toBe(false)
  })

  it('renders required dimensions and submits the selected active value', async () => {
    mockDimensionEditorApi()
    renderDimensionEditor()

    await chooseSelectOption('第 1 条分录会计科目', '6602.01 研发费用')
    await chooseSelectOption('第 2 条分录会计科目', '1002 银行存款')
    expect(await screen.findByRole('combobox', { name: '第 1 条分录部门（必填）' })).toBeInTheDocument()
    await waitFor(() => expect(mockedOpenApiMethod('POST').mock.calls.filter(
      ([path]) => path === '/v1/ledgers/{ledgerId}/dimension-values:batch',
    )).toHaveLength(1))
    expect(mockedOpenApiMethod('POST')).toHaveBeenCalledWith(
      '/v1/ledgers/{ledgerId}/dimension-values:batch',
      expect.objectContaining({ body: { dimensionTypeIds: ['dimension-department'] } }),
    )

    fireEvent.change(screen.getByLabelText('第 1 条分录借方金额'), { target: { value: '100' } })
    fireEvent.change(screen.getByLabelText('第 2 条分录贷方金额'), { target: { value: '100' } })
    fireEvent.click(screen.getByRole('button', { name: '保存并记账' }))
    expect(await screen.findByText('请选择部门')).toBeInTheDocument()
    expect(vi.mocked(apiFetch).mock.calls.some(([, , options]) => options?.method === 'POST')).toBe(false)

    await chooseSelectOption('第 1 条分录部门（必填）', 'RD 研发部')
    fireEvent.click(screen.getByRole('button', { name: '保存并记账' }))

    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([, , options]) => options?.method === 'POST')).toBe(true))
    const createCall = vi.mocked(apiFetch).mock.calls.find(([, , options]) => options?.method === 'POST')!
    expect(JSON.parse(String(createCall[2]?.body)).lines[0].dimensions).toEqual([{
      dimensionTypeId: 'dimension-department', dimensionValueId: 'department-rd',
    }])
  }, 10_000)

  it('clears obsolete dimension values when the account changes', async () => {
    mockDimensionEditorApi()
    renderDimensionEditor()

    await chooseSelectOption('第 1 条分录会计科目', '6602.01 研发费用')
    await chooseSelectOption('第 1 条分录部门（必填）', 'RD 研发部')
    await chooseSelectOption('第 1 条分录会计科目', '1002 银行存款')
    await waitFor(() => expect(screen.queryByRole('combobox', { name: '第 1 条分录部门（必填）' })).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '保存并记账' }))

    await waitFor(() => expect(vi.mocked(apiFetch).mock.calls.some(([, , options]) => options?.method === 'POST')).toBe(true))
    const createCall = vi.mocked(apiFetch).mock.calls.find(([, , options]) => options?.method === 'POST')!
    expect(JSON.parse(String(createCall[2]?.body)).lines[0].dimensions).toEqual([])
  })

  it('groups debit and credit amounts into adjacent voucher columns', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/new']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('columnheader', { name: '借方金额' })).toBeInTheDocument()
    expect(screen.getByRole('columnheader', { name: '贷方金额' })).toBeInTheDocument()
    expect(screen.queryByRole('columnheader', { name: '方向' })).not.toBeInTheDocument()
    const debitInput = screen.getByRole('textbox', { name: '第 1 条分录借方金额' })
    const creditInput = screen.getByRole('textbox', { name: '第 1 条分录贷方金额' })
    expect(debitInput).toHaveValue('')
    expect(creditInput).toHaveValue('')

    fireEvent.change(debitInput, { target: { value: '10' } })
    expect(debitInput).toHaveValue('10')
    expect(creditInput).toHaveValue('')

    fireEvent.change(creditInput, { target: { value: '20' } })
    expect(debitInput).toHaveValue('')
    expect(creditInput).toHaveValue('20')
  })

  it('keeps a negative amount on its selected side', async () => {
    expect(voucherAmountPattern.test('-25.50')).toBe(true)
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/new']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    const debitInput = await screen.findByRole('textbox', { name: '第 1 条分录借方金额' })
    fireEvent.change(debitInput, { target: { value: '-25.50' } })

    expect(debitInput).toHaveValue('-25.50')
    expect(screen.getByText('-25.50')).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: '第 1 条分录贷方金额' })).toHaveValue('')
  })

  it('allows editing a posted generated voucher while its period is open', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(path.endsWith('/periods') ? [{
      id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN',
    }] : path.endsWith('/voucher-1') ? {
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-25',
      voucherType: '记', voucherNumber: '6', summary: '收货款', status: 'POSTED',
      approvalRequired: false, version: 2, lines: [], sourceType: 'FIXED_ASSET', sourceId: 'asset-1',
    } : []))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/voucher-1']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('POSTED')).toBeInTheDocument()
    expect(screen.getByText('会计期间 2026-06（保存后不可修改）')).toBeInTheDocument()
    expect(screen.queryByLabelText('会计期间')).not.toBeInTheDocument()
    expect(await screen.findByRole('button', { name: '保存修改' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '删除凭证' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '校验' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '记账' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '反记账' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /冲\s*销/ })).not.toBeInTheDocument()
  })

  it('does not expose editing actions before the voucher period is resolved', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.endsWith('/periods')) return new Promise(() => {})
      if (path.endsWith('/voucher-1')) return Promise.resolve({
        id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-25',
        voucherType: '记', voucherNumber: '6', summary: '收货款', status: 'POSTED',
        approvalRequired: false, version: 2, lines: [],
      })
      return Promise.resolve([])
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/voucher-1']}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('正在读取凭证会计期间')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存修改' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '删除凭证' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: /分录摘要/ })).not.toBeInTheDocument()
  })

  it('keeps a posted voucher read-only after the period is closed', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(path.endsWith('/periods') ? [{
      id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'CLOSED',
    }] : path.endsWith('/voucher-1') ? {
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-25',
      voucherType: '记', voucherNumber: '6', summary: '收货款', status: 'POSTED',
      approvalRequired: false, version: 2, lines: [],
    } : []))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/vouchers/voucher-1']}>
            <Routes>
              <Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
            </Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    expect(await screen.findByText('已结账')).toHaveAttribute('role', 'status')
    expect(screen.queryByText('期间已结账，不能修改或删除凭证')).not.toBeInTheDocument()
    expect(screen.getByLabelText('已结账凭证，只读')).toHaveAttribute('inert')
    expect(screen.queryByRole('button', { name: '保存修改' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '删除凭证' })).not.toBeInTheDocument()
  })
})

describe('cash-flow voucher classification', () => {
  const baseAccount = {
    ledgerId: 'ledger-1', category: 'CURRENT_ASSET', normalBalance: 'DEBIT', status: 'ACTIVE',
    parentId: null, level: 1, isLeaf: true, isTemplate: false, hasBusinessUsage: false, coreLocked: false,
    legacyCode: false, version: 0, quantityEnabled: false, unitName: null, createdAt: null,
    dimensionRequirements: [],
  }
  // Like the SME template: cash accounts are NOT flagged cashFlowRequired; they are
  // identified through the published formula's cash account references.
  const cashAccount = {
    ...baseAccount, id: 'account-cash', code: '1001', name: '库存现金', standardAccountKey: 'ASSET.CASH', cashFlowRequired: false, defaultCashFlowItemId: null,
  }
  const bankAccount = {
    ...baseAccount, id: 'account-bank', code: '1002', name: '银行存款', standardAccountKey: 'ASSET.BANK_DEPOSIT', cashFlowRequired: false, defaultCashFlowItemId: 'item-sales',
  }
  const expenseAccount = {
    ...baseAccount, id: 'account-expense', code: '6602', name: '管理费用', standardAccountKey: null, cashFlowRequired: false, defaultCashFlowItemId: null,
  }
  const items = [
    { id: 'item-sales', ledgerId: 'ledger-1', code: 'SME_CF_01_SALES_RECEIPTS', name: '销售产成品、商品、提供劳务收到的现金', status: 'ACTIVE', template: true },
    { id: 'item-tax', ledgerId: 'ledger-1', code: 'SME_CF_05_TAX_PAYMENTS', name: '支付的税费', status: 'ACTIVE', template: true },
    { id: 'item-inactive', ledgerId: 'ledger-1', code: 'SME_CF_02_OTHER_OPERATING_RECEIPTS', name: '收到其他与经营活动有关的现金', status: 'INACTIVE', template: true },
    { id: 'item-not-in-formula', ledgerId: 'ledger-1', code: 'SME_CF_03_PURCHASE_PAYMENTS', name: '购买原材料支付的现金', status: 'ACTIVE', template: true },
  ]
  const cashAccountRefs = [
    { type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.CASH' },
    { type: 'STANDARD_ACCOUNT_KEY', value: 'ASSET.BANK_DEPOSIT' },
  ]
  const formulaWorkspace = {
    code: 'CASH_FLOW', name: '现金流量表', kind: 'FIXED_LINES', reportType: 'CASH_FLOW',
    templateCode: 'SME-2011-17-CASH-FLOW', publishedVersion: 1,
    publishedDefinition: {
      schemaVersion: 1, kind: 'FIXED_LINES', reportType: 'CASH_FLOW', templateCode: 'SME-2011-17-CASH-FLOW',
      columnPolicy: { primary: 'ACTIVITY', comparative: 'ACTIVITY' },
      groups: [{
        key: 'OPERATING', title: '一、经营活动产生的现金流量', lines: [
          { key: 'cf-1', lineNo: 1, indent: 0, rowType: 'DETAIL', name: '销售收到的现金', expression: { type: 'CASH_FLOW_ITEM_AMOUNT', direction: 'INFLOW', itemCodes: ['SME_CF_01_SALES_RECEIPTS'], cashAccounts: cashAccountRefs } },
          { key: 'cf-5', lineNo: 5, indent: 0, rowType: 'DETAIL', name: '支付的税费', expression: { type: 'CASH_FLOW_ITEM_AMOUNT', direction: 'OUTFLOW', itemCodes: ['SME_CF_05_TAX_PAYMENTS'], cashAccounts: cashAccountRefs } },
        ],
      }],
      rules: [], checks: [],
    },
    draft: null,
  }
  const cashFlowLine = (accountId: string, side: 'DEBIT' | 'CREDIT', amount: string, cashFlowItemId?: string) => ({
    accountId, side, currency: 'CNY', originalAmount: amount, exchangeRate: '1', cashFlowItemId,
  })
  const persistedDraftVoucher = {
    id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-open', voucherDate: '2026-08-16',
    voucherType: '记', voucherNumber: '5', summary: '外部现金收支', status: 'DRAFT', approvalRequired: false, version: 1,
    lines: [
      { id: 'line-1', lineNo: 1, accountId: 'account-cash', side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', baseAmount: '100.00', summary: null, cashFlowItemId: 'item-sales', quantity: null, unitPrice: null, dimensions: [] },
      { id: 'line-2', lineNo: 2, accountId: 'account-expense', side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', baseAmount: '100.00', summary: null, cashFlowItemId: null, quantity: null, unitPrice: null, dimensions: [] },
    ],
  }
  const staleVoucher = {
    ...persistedDraftVoucher,
    id: 'voucher-stale',
    lines: [
      { id: 'line-1', lineNo: 1, accountId: 'account-cash', side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', baseAmount: '100.00', summary: null, cashFlowItemId: 'item-inactive', quantity: null, unitPrice: null, dimensions: [] },
      { id: 'line-2', lineNo: 2, accountId: 'account-expense', side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', baseAmount: '100.00', summary: null, cashFlowItemId: null, quantity: null, unitPrice: null, dimensions: [] },
    ],
  }

  const installCashFlowBackend = (options: { formulaError?: boolean; postError?: unknown } = {}) => {
    const postedBodies: Record<string, unknown>[] = []
    vi.mocked(apiFetch).mockImplementation((path, _session, init) => {
      if (path.endsWith('/periods')) return Promise.resolve([{
        id: 'period-open', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: true,
      }])
      if (path.endsWith('/accounts')) return Promise.resolve([cashAccount, bankAccount, expenseAccount])
      if (path.endsWith('/cash-flow-items')) return Promise.resolve(items)
      if (path.endsWith('/report-formulas/CASH_FLOW')) {
        if (options.formulaError) return Promise.reject(new ApiError(500, { code: 'STATUTORY_FORMULA_NOT_FOUND', title: '法定报表公式缺失', detail: '缺少公式' }))
        return Promise.resolve(formulaWorkspace)
      }
      if (path.endsWith('/vouchers/voucher-1')) {
        return Promise.resolve(persistedDraftVoucher)
      }
      if (path.endsWith('/vouchers/voucher-stale')) {
        return Promise.resolve(staleVoucher)
      }
      if (path.includes('/vouchers') && init?.method === 'POST') {
        if (options.postError) return Promise.reject(options.postError)
        if (path.endsWith('/vouchers')) {
          postedBodies.push(JSON.parse(String(init.body)))
          return Promise.resolve({
            id: 'voucher-created', ledgerId: 'ledger-1', periodId: 'period-open', voucherDate: '2026-08-16',
            voucherType: '记', voucherNumber: '1', summary: null, status: 'POSTED', approvalRequired: false, version: 0, lines: [],
          })
        }
        return Promise.resolve(persistedDraftVoucher)
      }
      return Promise.resolve([])
    })
    return postedBodies
  }

  const renderEditor = (initial = '/ledgers/ledger-1/vouchers/new') => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={[initial]}>
            <Routes><Route path="/ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )
  }
  const chooseSelectOption = async (label: string, optionName: string) => {
    fireEvent.mouseDown(screen.getByRole('combobox', { name: label }))
    await screen.findAllByText(optionName)
    const openDropdowns = Array.from(document.querySelectorAll<HTMLElement>('.ant-select-dropdown:not(.ant-select-dropdown-hidden)'))
    fireEvent.click(within(openDropdowns.at(-1)!).getByText(optionName))
  }
  const fillAmount = (index: number, side: '借方' | '贷方', amount: string) => {
    fireEvent.change(screen.getByLabelText(`第 ${index} 条分录${side}金额`), { target: { value: amount } })
  }
  const save = async () => {
    fireEvent.click(screen.getByRole('button', { name: '保存并记账' }))
  }

  describe('validateCashFlowLines', () => {
    // Cash accounts are identified via the published formula, not the cashFlowRequired flag.
    const isCashAccount = (accountId?: string) => accountId === 'account-cash' || accountId === 'account-bank'
    const reportable = items.filter((item) => item.status === 'ACTIVE' && (item.code === 'SME_CF_01_SALES_RECEIPTS' || item.code === 'SME_CF_05_TAX_PAYMENTS'))

    it('requires an item on every cash line when a non-cash counterpart exists', () => {
      const errors = validateCashFlowLines([
        cashFlowLine('account-cash', 'DEBIT', '100'),
        cashFlowLine('account-expense', 'CREDIT', '100'),
      ], isCashAccount, reportable)
      expect(errors).toEqual([{ lineIndex: 0, message: '第 1 条分录的现金收支必须选择现金流项目' }])
    })

    it('checks compound vouchers line by line', () => {
      const errors = validateCashFlowLines([
        cashFlowLine('account-cash', 'DEBIT', '100', 'item-sales'),
        cashFlowLine('account-bank', 'DEBIT', '50'),
        cashFlowLine('account-expense', 'CREDIT', '150'),
      ], isCashAccount, reportable)
      expect(errors.map((error) => error.lineIndex)).toEqual([1])
    })

    it('exempts pure cash internal transfers', () => {
      expect(validateCashFlowLines([
        cashFlowLine('account-cash', 'DEBIT', '100'),
        cashFlowLine('account-bank', 'CREDIT', '100'),
      ], isCashAccount, reportable)).toEqual([])
    })

    it('rejects inactive or non-formula items with a located message', () => {
      const errors = validateCashFlowLines([
        cashFlowLine('account-cash', 'DEBIT', '100', 'item-inactive'),
        cashFlowLine('account-expense', 'CREDIT', '100'),
      ], isCashAccount, reportable)
      expect(errors[0].message).toContain('不在当前报表公式中')
    })

    it('skips validation when the published formula is unavailable', () => {
      expect(validateCashFlowLines([
        cashFlowLine('account-cash', 'DEBIT', '100'),
        cashFlowLine('account-expense', 'CREDIT', '100'),
      ], isCashAccount, null)).toEqual([])
    })

    it('ignores vouchers without cash lines', () => {
      expect(validateCashFlowLines([
        cashFlowLine('account-expense', 'DEBIT', '100'),
        cashFlowLine('account-expense', 'CREDIT', '100'),
      ], isCashAccount, reportable)).toEqual([])
    })
  })

  it('blocks saving an external cash receipt without a cash-flow item', async () => {
    const postedBodies = installCashFlowBackend()
    renderEditor()
    await screen.findByRole('heading', { name: '记账凭证' })

    await chooseSelectOption('第 1 条分录会计科目', '1001 库存现金')
    await chooseSelectOption('第 2 条分录会计科目', '6602 管理费用')
    fillAmount(1, '借方', '100')
    fillAmount(2, '贷方', '100')
    await save()

    // The inline row-level rule points at the exact cash line and the request never leaves.
    expect(await screen.findByText('请选择现金流项目')).toBeInTheDocument()
    await waitFor(() => expect(postedBodies).toHaveLength(0))
  })

  it('auto-fills the account default item when it is still reportable', async () => {
    installCashFlowBackend()
    renderEditor()
    await screen.findByRole('heading', { name: '记账凭证' })

    await chooseSelectOption('第 1 条分录会计科目', '1002 银行存款')
    await chooseSelectOption('第 2 条分录会计科目', '6602 管理费用')
    fillAmount(1, '借方', '100')
    fillAmount(2, '贷方', '100')

    const itemSelect = await screen.findByRole('combobox', { name: '第 1 条分录现金流项目' })
    expect(itemSelect).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText('SME_CF_01_SALES_RECEIPTS 销售产成品、商品、提供劳务收到的现金')).toBeInTheDocument())
  })

  it('only offers active items referenced by the published formula', async () => {
    installCashFlowBackend()
    renderEditor()
    await screen.findByRole('heading', { name: '记账凭证' })

    await chooseSelectOption('第 1 条分录会计科目', '1001 库存现金')
    await waitFor(() => expect(screen.getByRole('combobox', { name: '第 1 条分录现金流项目' })).toBeInTheDocument())
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '第 1 条分录现金流项目' }))

    await waitFor(() => expect(screen.getByText('SME_CF_01_SALES_RECEIPTS 销售产成品、商品、提供劳务收到的现金')).toBeInTheDocument())
    expect(screen.getByText('SME_CF_05_TAX_PAYMENTS 支付的税费')).toBeInTheDocument()
    expect(screen.queryByText('收到其他与经营活动有关的现金')).not.toBeInTheDocument()
    expect(screen.queryByText('购买原材料支付的现金')).not.toBeInTheDocument()
  })

  it('keeps internal transfers optional and still posts', async () => {
    const postedBodies = installCashFlowBackend()
    renderEditor()
    await screen.findByRole('heading', { name: '记账凭证' })

    await chooseSelectOption('第 1 条分录会计科目', '1001 库存现金')
    await chooseSelectOption('第 2 条分录会计科目', '1002 银行存款')
    fillAmount(1, '借方', '100')
    fillAmount(2, '贷方', '100')
    await save()

    await waitFor(() => expect(postedBodies).toHaveLength(1))
    const lines = postedBodies[0].lines as { cashFlowItemId?: string }[]
    expect(lines[0].cashFlowItemId).toBeUndefined()
  })

  it('keeps a clear message when the backend rejects classification after formula changes', async () => {
    installCashFlowBackend({
      formulaError: true,
      postError: new ApiError(422, { code: 'CASH_FLOW_ITEM_NOT_REPORTABLE', title: '现金流项目不可用', detail: '第 1 行使用的现金流项目不在当前报表公式中：SME_CF_01_SALES_RECEIPTS' }),
    })
    renderEditor('/ledgers/ledger-1/vouchers/voucher-1')
    await screen.findByRole('heading', { name: '记账凭证' })

    // The published formula is unavailable, so the frontend skips its own check and the
    // backend's 422 must still surface as a clear, located message.
    fireEvent.click(screen.getByRole('button', { name: /^校\s*验$/ }))
    expect((await screen.findAllByText('现金流项目不符合要求')).length).toBeGreaterThan(0)
    expect(screen.getByText(/第 1 行使用的现金流项目不在当前报表公式中：SME_CF_01_SALES_RECEIPTS/)).toBeInTheDocument()
  })

  it('shows a stale persisted item clearly marked instead of a raw id', async () => {
    installCashFlowBackend()
    renderEditor('/ledgers/ledger-1/vouchers/voucher-stale')
    await screen.findByRole('heading', { name: '记账凭证' })

    await waitFor(() => expect(screen.getByText('SME_CF_02_OTHER_OPERATING_RECEIPTS 收到其他与经营活动有关的现金（已停用或不在公式中）')).toBeInTheDocument())
  })

  it('preserves cashFlowItemId in the save request body', () => {
    const body = buildVoucherRequestBody({
      voucherDate: dayjs('2026-08-16'),
      voucherType: '记',
      lines: [
        { accountId: 'account-cash', side: 'DEBIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1', cashFlowItemId: 'item-sales' },
        { accountId: 'account-expense', side: 'CREDIT', currency: 'CNY', originalAmount: '100', exchangeRate: '1' },
        { side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' },
      ],
    }, false)
    expect(body.lines[0]).toHaveProperty('cashFlowItemId', 'item-sales')
  })
})
