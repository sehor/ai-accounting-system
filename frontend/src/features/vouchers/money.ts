import Decimal from 'decimal.js'

export function decimalOrZero(value: unknown): Decimal {
  try {
    return new Decimal(String(value ?? '').trim() || '0')
  } catch {
    return new Decimal(0)
  }
}

export function voucherTotals(lines: Array<{ side: 'DEBIT' | 'CREDIT'; originalAmount: unknown; exchangeRate: unknown }>) {
  return lines.reduce((total, line) => {
    const amount = decimalOrZero(line.originalAmount).times(decimalOrZero(line.exchangeRate))
    return line.side === 'DEBIT' ? { debit: total.debit.plus(amount), credit: total.credit } : { debit: total.debit, credit: total.credit.plus(amount) }
  }, { debit: new Decimal(0), credit: new Decimal(0) })
}
