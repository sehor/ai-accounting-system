import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntApp } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { AdminPage } from './AdminPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserName: 'admin' } }),
}))

describe('AdminPage', () => {
  beforeAll(() => {
    const getComputedStyle = window.getComputedStyle
    window.getComputedStyle = ((element: Element) => getComputedStyle(element)) as typeof window.getComputedStyle
    window.matchMedia = vi.fn().mockImplementation(() => ({
      matches: false,
      addListener: vi.fn(),
      removeListener: vi.fn(),
      addEventListener: vi.fn(),
      removeEventListener: vi.fn(),
      dispatchEvent: vi.fn(),
    }))
  })

  beforeEach(() => {
    vi.mocked(apiFetch).mockImplementation(async (path) => {
      if (path === '/admin/users') return [
        { id: 'admin-id', issuer: 'local', subject: 'admin-id', displayName: 'admin', email: null,
          userType: 'HUMAN', status: 'ACTIVE', deleted: false, protectedUser: true },
        { id: 'tester-id', issuer: 'local', subject: 'tester-id', displayName: 'tester', email: null,
          userType: 'HUMAN', status: 'ACTIVE', deleted: false, protectedUser: false },
      ]
      if (path === '/admin/ledgers') return [
        { id: 'ledger-id', name: '测试账套', accountingStandardCode: 'SME',
          accountingStandardVersion: '2011-17', baseCurrency: 'CNY', startDate: '2026-01-01',
          approvalEnabled: false, status: 'ACTIVE', deleted: false },
      ]
      if (path === '/ledgers/ledger-id/members') return []
      return undefined
    })
  })

  afterEach(() => {
    cleanup()
    vi.mocked(apiFetch).mockReset()
  })

  it('loads all users and ledgers from the administration API', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><AntApp><AdminPage /></AntApp></QueryClientProvider>)

    expect(await screen.findByText('tester')).toBeInTheDocument()
    expect(screen.getByText('admin')).toBeInTheDocument()
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith('/admin/ledgers', { localUserName: 'admin' }))
  })

  it('opens permission assignment for any ledger', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><AntApp><AdminPage /></AntApp></QueryClientProvider>)

    fireEvent.click(await screen.findByRole('tab', { name: '账套（1）' }))
    fireEvent.click(await screen.findByRole('button', { name: '分配权限' }))

    expect(await screen.findByText('分配账套权限：测试账套')).toBeInTheDocument()
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-id/members',
      { localUserName: 'admin' },
    ))
  })

})
