import { cleanup, render, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { ReactNode } from 'react'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch, apiFetchWithHeaders } from '../api/client'
import { BooksPage } from './BooksPage'
import { ReportsPage } from './ReportsPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn(), apiFetchWithHeaders: vi.fn() }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

const periods = [
  { id: 'period-6', ledgerId: 'ledger-1', periodCode: '2026-06', startDate: '2026-06-01', endDate: '2026-06-30', status: 'OPEN', hasVouchers: true },
  { id: 'period-8', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: false },
]

beforeAll(() => {
  const getComputedStyle = window.getComputedStyle
  window.matchMedia = (query: string) => ({
    matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(),
    addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  })
  window.getComputedStyle = ((element: Element) => getComputedStyle(element)) as typeof window.getComputedStyle
})

beforeEach(() => {
  vi.mocked(apiFetchWithHeaders).mockResolvedValue({
    data: { totalLines: 0, lines: [] }, headers: new Headers(),
  })
  vi.mocked(apiFetch).mockImplementation((path) => {
    if (path.endsWith('/periods')) return Promise.resolve(periods)
    if (path.endsWith('/accounts')) return Promise.resolve([{
      id: 'account-1', ledgerId: 'ledger-1', code: '1002', name: '银行存款', category: 'ASSET',
      normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 1, isLeaf: true,
      isTemplate: false, hasBusinessUsage: true, coreLocked: false, legacyCode: false, version: 0,
      cashFlowRequired: false, defaultCashFlowItemId: null, quantityEnabled: false, unitName: null,
      dimensionRequirements: [],
    }])
    if (path.includes('/books/sub-ledger')) return Promise.resolve({
      periodFrom: '2026-06', periodTo: '2026-06', periodCode: '2026-06',
      accountId: 'account-1', accountCode: '1002', accountName: '银行存款',
      openingDirection: 'DEBIT', openingBalance: '100.00', data: [], periodDebit: '0', periodCredit: '0',
      endingDirection: 'DEBIT', endingBalance: '100.00',
      pagination: { page: 1, pageSize: 50, totalItems: 0, totalPages: 0 },
    })
    if (path.includes('/reports/income-statement')) return Promise.resolve({ periodCode: '2026-08', lines: [] })
    return Promise.resolve([])
  })
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

function renderRoute(entry: string, path: string, element: ReactNode) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <App>
        <MemoryRouter initialEntries={[entry]}>
          <Routes><Route path={path} element={element} /></Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('independent book and report periods', () => {
  it('keeps a June sub-ledger request scoped to the sub-ledger tab', async () => {
    renderRoute(
      '/ledgers/ledger-1/books/sub-ledger?periodCode=2026-06&accountId=account-1',
      '/ledgers/:ledgerId/books/:bookType',
      <BooksPage />,
    )

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      expect.stringContaining('/books/sub-ledger?periodFrom=2026-06&periodTo=2026-06&accountId=account-1'),
      expect.anything(),
    ))
  })

  it('keeps an August report request scoped to the report tab', async () => {
    renderRoute(
      '/ledgers/ledger-1/reports/income-statement?periodCode=2026-08',
      '/ledgers/:ledgerId/reports/:reportType',
      <ReportsPage />,
    )

    await waitFor(() => expect(apiFetchWithHeaders).toHaveBeenCalledWith(
      expect.stringContaining('/reports/income-statement?periodFrom=2026-08&periodTo=2026-08'),
      expect.anything(),
    ))
    expect(apiFetchWithHeaders).not.toHaveBeenCalledWith(
      expect.stringContaining('/reports/income-statement?periodFrom=2026-06&periodTo=2026-06'),
      expect.anything(),
    )
  })
})
