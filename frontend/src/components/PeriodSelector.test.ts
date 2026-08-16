import { describe, expect, it } from 'vitest'
import { selectDefaultPeriod, type PeriodOption } from './PeriodSelector'

const period = (periodCode: string, hasVouchers = false): PeriodOption => ({
  id: periodCode,
  ledgerId: 'ledger-1',
  periodCode,
  startDate: `${periodCode}-01`,
  endDate: `${periodCode}-28`,
  status: 'OPEN',
  hasVouchers,
})

describe('selectDefaultPeriod', () => {
  it('prefers the last period containing a non-deleted voucher', () => {
    expect(selectDefaultPeriod([
      period('2026-05', true), period('2026-06', true), period('2026-08'),
    ], '2026-08')).toBe('2026-06')
  })

  it('falls back to the natural current month and then the latest period', () => {
    expect(selectDefaultPeriod([period('2026-06'), period('2026-08')], '2026-08')).toBe('2026-08')
    expect(selectDefaultPeriod([period('2026-05'), period('2026-06')], '2026-08')).toBe('2026-06')
  })
})
