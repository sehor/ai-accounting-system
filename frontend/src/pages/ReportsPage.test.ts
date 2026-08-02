import { describe, expect, it } from 'vitest'
import { reportRowKey } from './ReportsPage'

describe('reportRowKey', () => {
  it('distinguishes multiple ledger lines from the same voucher', () => {
    const line = { voucherId: 'voucher-1', voucherNumber: '1', voucherDate: '2026-01-01', accountCode: '1001', accountName: '现金', side: 'DEBIT', amount: '100', dimensionKey: null }

    expect(reportRowKey(line, 0)).not.toBe(reportRowKey(line, 1))
  })
})
