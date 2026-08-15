import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'
import { LedgerListPage } from './LedgerListPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
})

installLegacyOpenApiBridge(apiFetch)

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

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

const ledger = {
  id: 'ledger-1',
  name: '测试账套',
  description: '',
  accountingStandardCode: 'SME',
  accountingStandardVersion: '2011-17',
  baseCurrency: 'CNY',
  startDate: '2026-01-01',
  approvalEnabled: false,
  status: 'ACTIVE',
}

function Location() {
  return <output data-testid="location">{useLocation().pathname}</output>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/ledgers']}>
          <Routes>
            <Route path="*" element={<><LedgerListPage /><Location /></>} />
          </Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('LedgerListPage', () => {
  it('creates a ledger with the initialized form values and opens it', async () => {
    vi.mocked(apiFetch).mockImplementation((path, _auth, init) => {
      if (path === '/accounting-standards') return Promise.resolve([{
        code: 'SME', version: '2011-17', name: '小企业会计准则', effectiveDate: '2013-01-01',
        accountCodeRule: { level2Width: 2, level3Width: 2, level4Width: 2 },
      }])
      if (path === '/ledgers' && init?.method === 'POST') return Promise.resolve(ledger)
      return Promise.resolve([])
    })
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: /新建账套/ }))
    fireEvent.change(screen.getByLabelText('账套名称'), { target: { value: '测试账套' } })
    fireEvent.click(screen.getByRole('button', { name: '创建并初始化' }))

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers',
      { localUserId: 'user-1', localUserName: 'admin' },
      expect.objectContaining({ method: 'POST' }),
    ))
    const createCall = vi.mocked(apiFetch).mock.calls.find(([, , init]) => init?.method === 'POST')
    expect(JSON.parse(String(createCall?.[2]?.body))).toMatchObject({
      name: '测试账套',
      accountingStandardCode: 'SME',
      accountingStandardVersion: '2011-17',
      accountCodeRule: { level2Width: 2, level3Width: 2, level4Width: 2 },
    })
    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/ledgers/ledger-1/overview'))
  })

  it.each([
    ['导入期初余额', '/ledgers/ledger-1/settings/openings'],
    ['导入科目表', '/ledgers/ledger-1/accounts'],
    ['导入金蝶凭证列表', '/ledgers/ledger-1/vouchers'],
  ])('navigates from %s to the existing workflow', async (label, expectedPath) => {
    vi.mocked(apiFetch).mockImplementation((path) => Promise.resolve(path === '/ledgers' ? [ledger] : []))
    renderPage()

    fireEvent.click(await screen.findByRole('button', { name: label }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(expectedPath))
  })
})
