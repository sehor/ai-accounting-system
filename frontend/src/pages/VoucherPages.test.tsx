import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { VoucherEditorPage, VoucherListPage } from './VoucherPages'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
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
    path.includes('kingdee:export') ? new Blob(['xlsx']) : [],
  ))
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

describe('VoucherListPage', () => {
  it('shows a clear processing entry for draft vouchers', async () => {
    vi.mocked(apiFetch).mockResolvedValueOnce([{
      id: 'voucher-1', ledgerId: 'ledger-1', periodId: 'period-1', voucherDate: '2026-06-11',
      voucherType: '记', voucherNumber: '1', summary: '缴纳社保', status: 'DRAFT',
      approvalRequired: false, version: 0, lines: [],
    }])
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
      if (path.includes('/vouchers?')) return Promise.resolve([voucher])
      if (path.endsWith(':post')) return Promise.resolve({ ...voucher, status: 'POSTED' })
      return Promise.resolve([])
    })
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
      if (path.includes('/vouchers?')) return Promise.resolve([voucher])
      if (path.endsWith(':approve')) return Promise.resolve({ ...voucher, status: 'APPROVED' })
      return Promise.resolve([])
    })
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

    fireEvent.click(screen.getByRole('button', { name: /导出金蝶凭证/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: '合并同类分录' }))
    fireEvent.click(within(screen.getByRole('dialog', { name: '导出金蝶凭证' }))
      .getByRole('button', { name: /^导\s*出$/ }))

    await waitFor(() => expect(downloadedFileName).toBe('kingdee-vouchers.xlsx'))
    expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/data-exchange/kingdee:export?mergeEntries=true',
      { localUserId: 'user-1', localUserName: 'admin' },
    )
    expect(createObjectUrl).toHaveBeenCalledWith(expect.any(Blob))
    expect(revokeObjectUrl).toHaveBeenCalledWith('blob:kingdee-vouchers')
  })
})

describe('VoucherEditorPage', () => {
  it('does not offer draft or posting actions after a voucher is posted', async () => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(path.endsWith('/voucher-1') ? {
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

    expect(await screen.findByText('版本 2 · POSTED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '保存草稿' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '校验' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: '记账' })).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '反记账' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /冲\s*销/ })).toBeInTheDocument()
  })
})
