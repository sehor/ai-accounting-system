import { cleanup, render, screen } from '@testing-library/react'
import { Outlet } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { App } from './App'

vi.mock('../auth/AuthProvider', () => ({
  AuthProvider: ({ children }: { children: React.ReactNode }) => children,
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))
vi.mock('../components/AppShell', () => ({ AppShell: () => <Outlet /> }))
vi.mock('../pages/LoginPage', () => ({ LoginPage: () => <div>login-route</div> }))
vi.mock('../pages/LedgerListPage', () => ({ LedgerListPage: () => <div>ledger-list-route</div> }))
vi.mock('../pages/FixedAssetListPage', () => ({ FixedAssetListPage: () => <div>fixed-asset-list-route</div> }))
vi.mock('../pages/FixedAssetPages', () => ({ FixedAssetEditorPage: () => <div>fixed-asset-editor-route</div> }))

describe('App lazy routes', () => {
  beforeAll(() => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }))
  })
  afterEach(cleanup)

  it('resolves the public login route through Suspense', async () => {
    window.history.pushState({}, '', '/login')
    render(<App />)
    expect(await screen.findByText('login-route')).toBeInTheDocument()
  })

  it('resolves an authenticated child route through the shared shell', async () => {
    window.history.pushState({}, '', '/ledgers')
    render(<App />)
    expect(await screen.findByText('ledger-list-route')).toBeInTheDocument()
  })

  it('keeps fixed-asset list and editor as separate route modules', async () => {
    window.history.pushState({}, '', '/ledgers/ledger-1/fixed-assets')
    const view = render(<App />)
    expect(await screen.findByText('fixed-asset-list-route')).toBeInTheDocument()
    view.unmount()
    window.history.pushState({}, '', '/ledgers/ledger-1/fixed-assets/asset-1')
    render(<App />)
    expect(await screen.findByText('fixed-asset-editor-route')).toBeInTheDocument()
  })
})
