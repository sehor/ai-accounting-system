import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { installLegacyOpenApiBridge } from '../test/openApiLegacyBridge'
import { SettingsPage } from './SettingsPage'

vi.mock('../api/client', async (importOriginal) => ({ ...(await importOriginal<typeof import('../api/client')>()), apiFetch: vi.fn() }))
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }) }))
installLegacyOpenApiBridge(apiFetch)

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: vi.fn().mockImplementation(() => ({
    matches: false, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
  })),
})

const ledger = {
  id: 'ledger-1', name: '测试账套', description: '', accountingStandardCode: 'SME', accountingStandardVersion: '2011-17',
  baseCurrency: 'CNY', startDate: '2026-01-01', approvalEnabled: false, cashFlowCheckEnabled: false, status: 'ACTIVE',
}

beforeEach(() => {
  vi.mocked(apiFetch).mockImplementation((path, _auth, init) => {
    if (path === '/ledgers/ledger-1') return Promise.resolve(init?.method === 'PATCH' ? { ...ledger, ...JSON.parse(String(init.body)) } : ledger)
    if (path.endsWith('/role')) return Promise.resolve({ role: 'OWNER' })
    return Promise.resolve([])
  })
})

afterEach(() => { cleanup(); vi.restoreAllMocks() })

describe('SettingsPage cash-flow switch', () => {
  it('saves the voucher cash-flow classification switch through the ledger PATCH endpoint', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><App><MemoryRouter initialEntries={['/ledgers/ledger-1/settings/periods']}><Routes><Route path="/ledgers/:ledgerId/settings/*" element={<SettingsPage />} /></Routes></MemoryRouter></App></QueryClientProvider>)

    const toggle = await screen.findByRole('switch', { name: '启用凭证现金流量核算' })
    expect(toggle).not.toBeChecked()
    await waitFor(() => expect(toggle).toBeEnabled())
    fireEvent.click(toggle)
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1',
      { localUserId: 'user-1', localUserName: 'admin' },
      expect.objectContaining({ method: 'PATCH' }),
    ))
    const patchCall = vi.mocked(apiFetch).mock.calls.find(([, , init]) => init?.method === 'PATCH')
    expect(JSON.parse(String(patchCall?.[2]?.body))).toMatchObject({ name: '测试账套', cashFlowCheckEnabled: true })
  })
})
