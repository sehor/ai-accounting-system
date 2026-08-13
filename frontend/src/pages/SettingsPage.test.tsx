import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, describe, expect, it, vi } from 'vitest'
import { OPENING_BALANCE_CSV_HEADER, OpeningsTab, openingBalanceAmountPattern } from './SettingsPage'

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

Object.defineProperty(URL, 'createObjectURL', {
  configurable: true,
  value: vi.fn(() => 'blob:opening-template'),
})

Object.defineProperty(URL, 'revokeObjectURL', {
  configurable: true,
  value: vi.fn(),
})

beforeAll(() => {
  const getComputedStyle = window.getComputedStyle
  window.getComputedStyle = ((element: Element) => getComputedStyle(element)) as typeof window.getComputedStyle
})

afterEach(() => {
  cleanup()
  vi.clearAllMocks()
})

const account = {
  id: 'account-1', ledgerId: 'ledger-1', code: '1001', name: '库存现金', category: 'CURRENT_ASSET',
  normalBalance: 'DEBIT', status: 'ACTIVE', parentId: null, level: 1, isLeaf: true, isTemplate: true,
  hasBusinessUsage: false, coreLocked: false, legacyCode: false, version: 0, cashFlowRequired: false,
  defaultCashFlowItemId: null, quantityEnabled: false, unitName: null, dimensionRequirements: [], createdAt: null,
}

const period = {
  id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-01', startDate: '2026-01-01',
  endDate: '2026-01-31', status: 'OPEN',
}

function renderOpenings(onSave = vi.fn()) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={client}>
      <App>
        <MemoryRouter initialEntries={['/ledgers/ledger-1/settings/openings']}>
          <Routes><Route path="*" element={<OpeningsTab
            rows={[{
              id: 'opening-1', ledgerId: 'ledger-1', periodId: period.id, accountId: account.id,
              currency: 'CNY', dimensionKey: '', debitOriginal: '0', creditOriginal: '0', exchangeRate: '1',
              debitBase: '0', creditBase: '0', confirmed: false,
            }]}
            accounts={[account]} periods={[period]} onSave={onSave} saving={false}
            onImport={vi.fn()} importing={false} onConfirm={vi.fn()} confirming={false}
          />} /></Routes>
        </MemoryRouter>
      </App>
    </QueryClientProvider>,
  )
}

describe('OpeningsTab', () => {
  it('shows the exact CSV contract and downloads a usable header-only template', () => {
    let downloadedFileName = ''
    vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(function (this: HTMLAnchorElement) {
      downloadedFileName = this.download
    })
    renderOpenings()

    expect(screen.getByText(OPENING_BALANCE_CSV_HEADER)).toBeInTheDocument()
    expect(screen.getByText(/不会把负数自动转到另一方向/)).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /下载 CSV 模板/ }))

    expect(downloadedFileName).toBe('opening-balances-template.csv')
    expect(URL.createObjectURL).toHaveBeenCalledWith(expect.any(Blob))
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:opening-template')
  })

  it('accepts negative debit and credit amounts in manual input', async () => {
    expect(openingBalanceAmountPattern.test('-25.50')).toBe(true)
    const onSave = vi.fn()
    renderOpenings(onSave)

    fireEvent.change(screen.getByRole('textbox', { name: '第 1 行借方金额' }), { target: { value: '-25.50' } })
    fireEvent.change(screen.getByRole('textbox', { name: '第 1 行贷方金额' }), { target: { value: '0' } })
    fireEvent.click(screen.getByRole('button', { name: /保\s*存/ }))

    await waitFor(() => expect(onSave).toHaveBeenCalledWith([
      expect.objectContaining({ debitOriginal: '-25.50', creditOriginal: '0', exchangeRate: '1' }),
    ]))
  })
})
