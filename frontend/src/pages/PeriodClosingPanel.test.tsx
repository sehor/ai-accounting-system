import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App, message } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { apiFetch } from '../api/client'
import { PeriodClosingPanel } from './PeriodClosingPanel'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, apiFetch: vi.fn() }
})

const period = { id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-08', startDate: '2026-08-01', endDate: '2026-08-31', status: 'OPEN' as const }
const status = {
  ledgerId: 'ledger-1', periodId: period.id, periodCode: period.periodCode, blockers: [], canClose: false,
  trialBalance: { openingDebit: '0', openingCredit: '0', periodDebit: '0', periodCredit: '0', closingDebit: '0', closingCredit: '0', openingDifference: '0', periodDifference: '0', closingDifference: '0', balanced: true },
  steps: ['DEPRECIATION', 'EXPENSE_TRANSFER'].map((step) => ({ step, status: 'PENDING', amount: '10', voucherId: null, inputFingerprint: null, blockers: [], updatedAt: '2026-08-01T00:00:00Z' })),
} as const

function renderPanel() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><App><PeriodClosingPanel ledgerId="ledger-1" session={{ localUserId: 'user-1' }} period={period} accounts={[]} onDismiss={vi.fn()} onConfirmClose={vi.fn()} /></App></QueryClientProvider>)
}

afterEach(() => vi.clearAllMocks())

describe('PeriodClosingPanel', () => {
  it('provides a dismiss button', async () => {
    vi.spyOn(message, 'useMessage').mockReturnValue([{ error: vi.fn() }, null] as never)
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.endsWith('/period-closing-settings')) return Promise.resolve({ ledgerId: 'ledger-1', profitAccountId: null, retainedEarningsAccountId: null, defaultProfitAccountId: null, defaultRetainedEarningsAccountId: null, version: 1 })
      return Promise.resolve(status)
    })
    renderPanel()

    expect(await screen.findByRole('button', { name: /关\s*闭/ })).toBeEnabled()
  })

  it('posts only the clicked step once and shows loading only on that card', async () => {
    vi.spyOn(message, 'useMessage').mockReturnValue([{ error: vi.fn() }, null] as never)
    let rejectGenerate: ((reason?: unknown) => void) | undefined
    let generationCount = 0
    vi.mocked(apiFetch).mockImplementation((path) => {
      if (path.includes('/steps/')) {
        generationCount += 1
        if (generationCount === 1) return new Promise((_resolve, reject) => { rejectGenerate = reject })
        return Promise.resolve(status.steps[1])
      }
      if (path.endsWith('/period-closing-settings')) return Promise.resolve({ ledgerId: 'ledger-1', profitAccountId: null, retainedEarningsAccountId: null, defaultProfitAccountId: null, defaultRetainedEarningsAccountId: null, version: 1 })
      return Promise.resolve(status)
    })
    renderPanel()

    const buttons = await screen.findAllByRole('button', { name: '生成凭证' })
    fireEvent.click(buttons[0])
    fireEvent.click(buttons[0])

    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/period-closings/period-1/steps/DEPRECIATION:generate',
      { localUserId: 'user-1' },
      { method: 'POST' },
    ))
    expect(vi.mocked(apiFetch).mock.calls.filter(([path]) => path.includes('/steps/'))).toHaveLength(1)
    expect(buttons[0]).toHaveClass('ant-btn-loading')
    expect(buttons[1]).not.toHaveClass('ant-btn-loading')
    expect(buttons[1]).toBeDisabled()
    fireEvent.click(buttons[1])
    expect(vi.mocked(apiFetch).mock.calls.filter(([path]) => path.includes('/steps/'))).toHaveLength(1)

    await act(async () => {
      rejectGenerate?.(new Error('generation failed'))
      await Promise.resolve()
    })
    await waitFor(() => {
      expect(buttons[0]).not.toHaveClass('ant-btn-loading')
      expect(buttons[1]).toBeEnabled()
    })

    fireEvent.click(buttons[1])
    await waitFor(() => expect(apiFetch).toHaveBeenCalledWith(
      '/ledgers/ledger-1/period-closings/period-1/steps/EXPENSE_TRANSFER:generate',
      { localUserId: 'user-1' },
      { method: 'POST' },
    ))
    expect(vi.mocked(apiFetch).mock.calls.filter(([path]) => path.includes('/steps/'))).toHaveLength(2)
  })
})
