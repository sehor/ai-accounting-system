import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { AccountsTab } from './AccountsTab'

vi.mock('../api/client', () => ({
  apiFetch: vi.fn().mockResolvedValue([]),
  jsonBody: vi.fn((value) => value),
  ApiError: class ApiError extends Error {},
}))

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

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

describe('AccountsTab account form', () => {
  it('does not warn when reopening the account form', async () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => {})
    const consoleWarn = vi.spyOn(console, 'warn').mockImplementation(() => {})
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <AccountsTab
            ledgerId="ledger-1"
            session={{ localUserId: 'user-1', localUserName: 'admin' }}
            accounts={[]}
            dimensionTypes={[]}
            periods={[]}
            loading={false}
            writable
            category="ASSET"
            onChanged={() => {}}
          />
        </App>
      </QueryClientProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: /新增一级科目/ }))
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: /新增一级科目/ }))

    const consoleOutput = [...consoleError.mock.calls, ...consoleWarn.mock.calls].flat().join(' ')
    expect(consoleOutput).not.toContain('Instance created by useForm is not connected')
    consoleError.mockRestore()
    consoleWarn.mockRestore()
  })

  it('adds and clears the account creation period export filter', async () => {
    const originalGetComputedStyle = window.getComputedStyle
    vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => originalGetComputedStyle(element))
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {})
    Object.defineProperty(URL, 'createObjectURL', {
      configurable: true,
      value: vi.fn(() => 'blob:account-export'),
    })
    Object.defineProperty(URL, 'revokeObjectURL', {
      configurable: true,
      value: vi.fn(),
    })
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <AccountsTab
            ledgerId="ledger-1"
            session={{ localUserId: 'user-1', localUserName: 'admin' }}
            accounts={[]}
            dimensionTypes={[]}
            periods={[{
              id: 'period-1',
              ledgerId: 'ledger-1',
              periodCode: '2026-03',
              startDate: '2026-03-01',
              endDate: '2026-03-31',
              status: 'OPEN',
            }]}
            loading={false}
            writable
            category="ASSET"
            onChanged={() => {}}
          />
        </App>
      </QueryClientProvider>,
    )

    fireEvent.mouseDown(screen.getByRole('combobox', { name: '创建期间' }))
    fireEvent.click(await screen.findByText(/2026-03（2026-03-01/))
    fireEvent.click(screen.getByRole('button', { name: /导出$/ }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/account-export?format=STANDARD&createdInPeriodId=period-1',
      { localUserId: 'user-1', localUserName: 'admin' },
    ))

    const clearButton = document.querySelector('.ant-select-clear')
    expect(clearButton).not.toBeNull()
    fireEvent.mouseDown(clearButton!)
    fireEvent.click(screen.getByRole('button', { name: /导出$/ }))
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/account-export?format=STANDARD',
      { localUserId: 'user-1', localUserName: 'admin' },
    ))
  })
})
