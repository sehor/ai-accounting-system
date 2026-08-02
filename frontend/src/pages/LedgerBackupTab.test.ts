import { describe, expect, it } from 'vitest'
import { backupFileError } from './LedgerBackupTab'

describe('backupFileError', () => {
  it('accepts a bounded .aibackup archive', () => {
    expect(backupFileError(new File(['PK'], 'ledger.aibackup'))).toBeNull()
  })

  it('rejects wrong extensions and oversized archives', () => {
    expect(backupFileError(new File(['PK'], 'ledger.zip'))).toContain('.aibackup')
    const oversized = new File(['PK'], 'ledger.aibackup')
    Object.defineProperty(oversized, 'size', { value: 100 * 1024 * 1024 + 1 })
    expect(backupFileError(oversized)).toContain('100 MiB')
  })
})
