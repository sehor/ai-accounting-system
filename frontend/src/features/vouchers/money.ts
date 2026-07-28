import Decimal from 'decimal.js'

export function voucherTotals(lines: Array<{ side: 'DEBIT' | 'CREDIT'; originalAmount: string; exchangeRate: string }>) {
  return lines.reduce((total, line) => {
    const amount = new Decimal(line.originalAmount).times(line.exchangeRate)
    return line.side === 'DEBIT' ? { debit: total.debit.plus(amount), credit: total.credit } : { debit: total.debit, credit: total.credit.plus(amount) }
  }, { debit: new Decimal(0), credit: new Decimal(0) })
}
