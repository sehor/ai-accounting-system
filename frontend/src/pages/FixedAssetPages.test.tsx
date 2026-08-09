import { describe, expect, it } from 'vitest'
import { formatFixedAssetMoney } from './FixedAssetPages'

describe('fixed asset presentation', () => {
  it('formats depreciation amounts with two decimals', () => {
    expect(formatFixedAssetMoney('1234.5')).toBe('1,234.50')
    expect(formatFixedAssetMoney(null)).toBe('-')
  })
})
