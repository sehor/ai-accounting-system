import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AccountsTab } from './AccountsTab'

vi.mock('../api/client', () => ({
  apiFetch: vi.fn().mockResolvedValue([]),
  jsonBody: vi.fn((value) => value),
  ApiError: class ApiError extends Error {},
}))

afterEach(cleanup)

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
})
