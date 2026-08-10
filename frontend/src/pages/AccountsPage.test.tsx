import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { AccountsPage } from './AccountsPage'

vi.mock('../api/client', () => ({
  apiFetch: vi.fn((path: string) => {
    if (path.endsWith('/accounts')) return Promise.resolve([])
    if (path.endsWith('/role')) return Promise.resolve({ role: 'OWNER' })
    return Promise.resolve([])
  }),
  jsonBody: vi.fn((value) => value),
  ApiError: class ApiError extends Error {},
}))

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

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?category=ASSET'))
    screen.getByRole('tab', { name: '负债' }).click()
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('?category=LIABILITY'))
  })
})
