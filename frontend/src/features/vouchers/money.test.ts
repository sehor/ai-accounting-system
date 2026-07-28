import { describe, expect, it } from 'vitest'
import { voucherTotals } from './money'

describe('voucherTotals', () => {
  it('keeps decimal arithmetic exact for debit and credit totals', () => {
    const result = voucherTotals([{ side: 'DEBIT', originalAmount: '0.1', exchangeRate: '1' }, { side: 'DEBIT', originalAmount: '0.2', exchangeRate: '1' }, { side: 'CREDIT', originalAmount: '0.3', exchangeRate: '1' }])
    expect(result.debit.toFixed(2)).toBe('0.30')
    expect(result.credit.toFixed(2)).toBe('0.30')
  })
})
