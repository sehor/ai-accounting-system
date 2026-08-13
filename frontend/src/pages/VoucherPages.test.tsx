import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, apiFetchWithHeaders } from '../api/client'
import { voucherAmountPattern, VoucherEditorPage, VoucherListPage } from './VoucherPages'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn(), apiFetchWithHeaders: vi.fn() }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

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

  it('shows a clear processing entry for draft vouchers', async () => {
    vi.mocked(apiFetchWithHeaders).mockResolvedValueOnce({ data: [{
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-11',
      voucherType: '记', voucherNumber: '1', summary: '缴纳社保', status: 'DRAFT',
      approvalRequired: false, version: 0, lines: [],
    }], headers: new Headers({ 'X-Total-Count': '1' }) })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

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

    expect(await screen.findByRole('link', { name: '继续处理' }))
      .toHaveAttribute('href', '/ledgers/ledger-1/vouchers/voucher-1')
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
    expect(await screen.findByRole('button', { name: '保存修改' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '删除凭证' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '校验' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '记账' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '反记账' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /冲\s*销/ })).not.toBeInTheDocument()
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

    expect(await screen.findByText('期间已结账，不能修改或删除凭证')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存修改' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '删除凭证' })).not.toBeInTheDocument()
  })
})
