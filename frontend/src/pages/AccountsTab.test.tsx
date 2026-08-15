import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'
import type { components } from '../api/generated'
import { AccountsTab } from './AccountsTab'

type Account = components['schemas']['Account']

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn().mockResolvedValue([]) }
})

installLegacyOpenApiBridge(apiFetch)

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

function account(id: string, name: string, createdAt: string, overrides: Partial<Account> = {}): Account {
  return {
    id, ledgerId: 'ledger-1', code: id, name, category: 'CURRENT_ASSET', normalBalance: 'DEBIT', status: 'ACTIVE',
    standardAccountKey: 'ASSET.CASH',
    parentId: null, level: 1, isLeaf: true, isTemplate: false, hasBusinessUsage: false, coreLocked: false,
    legacyCode: false, version: 0, cashFlowRequired: false, defaultCashFlowItemId: null,
    quantityEnabled: false, unitName: null, dimensionRequirements: [], createdAt, ...overrides,
  }
}

describe('AccountsTab account form', () => {
  it('allows an approved stable key when creating a top-level account', async () => {
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <AccountsTab
            ledgerId="ledger-1"
            session={{ localUserId: 'user-1', localUserName: 'admin' }}
            accounts={[account('1001', 'Cash', '2026-01-01T00:00:00+08:00', { isTemplate: true })]}
            dimensionTypes={[]}
            periods={[]}
            loading={false}
            writable
            category="CURRENT_ASSET"
            onChanged={() => {}}
          />
        </App>
      </QueryClientProvider>,
    )

    fireEvent.click(screen.getByRole('button', { name: /新增一级科目/ }))
    fireEvent.change(screen.getByLabelText('科目编码'), { target: { value: '1999' } })
    fireEvent.change(screen.getByLabelText('科目名称'), { target: { value: 'Custom cash' } })
    fireEvent.mouseDown(screen.getByRole('combobox', { name: '法定报表归类' }))
    expect(await screen.findAllByRole('option', { name: /ASSET.CASH/ })).not.toHaveLength(0)
  })

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
            category="CURRENT_ASSET"
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

  it('filters already loaded accounts by created at without another request', async () => {
    const originalGetComputedStyle = window.getComputedStyle
    vi.spyOn(window, 'getComputedStyle').mockImplementation((element) => originalGetComputedStyle(element))
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })

    render(
      <QueryClientProvider client={queryClient}>
        <App>
          <AccountsTab
            ledgerId="ledger-1"
            session={{ localUserId: 'user-1', localUserName: 'admin' }}
            accounts={[
              account('1001', 'March account', '2026-03-15T10:00:00+08:00'),
              account('1002', 'April account', '2026-04-01T10:00:00+08:00'),
              account('1003', 'Earlier parent', '2026-02-01T10:00:00+08:00', { isLeaf: false }),
              account('100301', 'March child', '2026-03-20T10:00:00+08:00', {
                parentId: '1003', level: 2,
              }),
            ]}
            dimensionTypes={[]}
            periods={[{
              id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-03',
              startDate: '2026-03-01', endDate: '2026-03-31', status: 'OPEN',
            }]}
            loading={false}
            writable
            category="CURRENT_ASSET"
            onChanged={() => {}}
          />
        </App>
      </QueryClientProvider>,
    )

    await waitFor(() => expect(apiFetch).toHaveBeenCalled())
    const requestsBeforeFiltering = vi.mocked(apiFetch).mock.calls.length
    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Created at' }))
    fireEvent.click(await screen.findByText(/2026-03/))

    expect(screen.getByText('March account')).toBeInTheDocument()
    expect(screen.getByText('Earlier parent')).toBeInTheDocument()
    expect(screen.getByText('March child')).toBeInTheDocument()
    expect(screen.queryByText('April account')).not.toBeInTheDocument()
    expect(apiFetch).toHaveBeenCalledTimes(requestsBeforeFiltering)
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
            category="CURRENT_ASSET"
            onChanged={() => {}}
          />
        </App>
      </QueryClientProvider>,
    )

    fireEvent.mouseDown(screen.getByRole('combobox', { name: 'Created at' }))
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
