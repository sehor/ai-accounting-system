import { describe, expect, it } from 'vitest'
import { describeTab } from './AppShell'
import { clearWorkspaceTabDirty, isWorkspaceTabDirty, setWorkspaceTabDirty } from './workspaceDirty'

describe('workspace tabs', () => {
  it('stores the period in each tab location instead of sharing it globally', () => {
    const subLedger = describeTab(
      '/ledgers/ledger-1/books/sub-ledger',
      '?periodCode=2026-06&accountId=account-1',
    )
    const incomeStatement = describeTab(
      '/ledgers/ledger-1/reports/income-statement',
      '?periodCode=2026-08',
    )

    expect(subLedger).toMatchObject({
      id: 'book-sub-ledger',
      location: '/ledgers/ledger-1/books/sub-ledger?periodCode=2026-06&accountId=account-1',
    })
    expect(incomeStatement).toMatchObject({
      id: 'report-income-statement',
      location: '/ledgers/ledger-1/reports/income-statement?periodCode=2026-08',
    })
  })

  it('tracks unsaved state by tab', () => {
    setWorkspaceTabDirty('voucher-new', true)
    expect(isWorkspaceTabDirty('voucher-new')).toBe(true)
    expect(isWorkspaceTabDirty('book-sub-ledger')).toBe(false)
    clearWorkspaceTabDirty('voucher-new')
    expect(isWorkspaceTabDirty('voucher-new')).toBe(false)
  })
})
