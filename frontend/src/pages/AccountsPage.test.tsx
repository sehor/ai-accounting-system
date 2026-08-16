import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'
import { AccountsPage } from './AccountsPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn((path: string) => {
    if (path.endsWith('/accounts')) return Promise.resolve([])
    if (path.endsWith('/role')) return Promise.resolve({ role: 'OWNER' })
    if (path.endsWith('/periods')) return Promise.resolve([{
      id: 'period-2026-08', ledgerId: 'ledger-1', periodCode: '2026-08',
      startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN', hasVouchers: false,
    }])
    return Promise.resolve([])
  }) }
})

installLegacyOpenApiBridge(apiFetch)

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

afterEach(() => vi.clearAllMocks())

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

function Location() {
  return <output data-testid="location">{useLocation().search}</output>
}

describe('AccountsPage', () => {
  it('uses the asset category by default and persists category changes in the URL', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <MemoryRouter initialEntries={['/ledgers/ledger-1/accounts']}>
            <Routes><Route path="/ledgers/:ledgerId/accounts" element={<><AccountsPage /><Location /></>} /></Routes>
          </MemoryRouter>
        </App>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?category=CURRENT_ASSET'))
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Created at' }))
    fireEvent.click(await screen.findByText('2026-08（2026-08-01 ~ 2026-08-31）'))
    expect(screen.getByRole('combobox', { name: 'Created at' }).closest('.ant-select'))
      .toHaveTextContent('2026-08')

    screen.getByRole('tab', { name: '流动负债' }).click()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?category=CURRENT_LIABILITY'))
    expect(screen.getByRole('combobox', { name: 'Created at' }).closest('.ant-select'))
      .toHaveTextContent('2026-08')
  })
})
