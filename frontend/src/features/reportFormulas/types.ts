import type { components } from '../../api/generated'

export type Account = components['schemas']['Account']

export type ReportType = 'BALANCE_SHEET' | 'INCOME_STATEMENT' | 'CASH_FLOW'

export type CashFlowDirection = 'INFLOW' | 'OUTFLOW' | 'NET'

export type AmountBasis = 'OPENING' | 'CLOSING'

export type CheckColumn = 'PRIMARY' | 'COMPARATIVE'

export interface FormulaDefinition {
  schemaVersion: number
  kind: 'FIXED_LINES' | 'ACCOUNT_DETAIL'
  reportType: ReportType
  templateCode: string
  columnPolicy: { primary: string; comparative: string }
  groups: FormulaGroup[]
  rules: DetailRule[]
  checks: FormulaCheck[]
  debitCategories?: string[]
  creditCategories?: string[]
  revenueCategories?: string[]
  expenseCategories?: string[]
}

export interface FormulaGroup {
  key: string
  title: string
  lines: FormulaLine[]
}

export interface FormulaLine {
  key: string
  lineNo: number
  indent: number
  rowType: string
  name: string
  expression: LineExpression
}

export type LineExpression = AccountAmountExpression | LinearCombinationExpression | CashFlowItemAmountExpression

export interface AccountAmountExpression {
  type: 'ACCOUNT_AMOUNT'
  operation: 'ACCOUNT_BALANCE' | 'ACCOUNT_ACTIVITY'
  side: 'DEBIT' | 'CREDIT'
  accounts: AccountReference[]
  /** Line-level amount basis (e.g. OPENING/CLOSING for the cash-flow balance rows). */
  basis?: AmountBasis
}

export interface LinearCombinationExpression {
  type: 'LINEAR_COMBINATION'
  components: LineComponent[]
}

/** Cash-flow line expression: sums posted external cash lines by item code. */
export interface CashFlowItemAmountExpression {
  type: 'CASH_FLOW_ITEM_AMOUNT'
  direction: CashFlowDirection
  itemCodes: string[]
  cashAccounts: AccountReference[]
}

export interface LineComponent {
  lineKey: string
  factor: number
}

export interface AccountReference {
  type: 'STANDARD_ACCOUNT_KEY' | 'ACCOUNT_ID'
  value: string
}

export interface DetailRule {
  key: string
  side: 'DEBIT' | 'CREDIT'
  categories: string[]
  accounts: AccountReference[]
}

export interface FormulaCheck {
  code: string
  name: string
  leftLineKey: string
  rightLineKey: string
  /** Which column the check applies to (PRIMARY or COMPARATIVE). */
  column?: CheckColumn
  /** Right side as a linear combination of earlier lines (statutory cash-flow checks). */
  rightComponents?: LineComponent[]
}

export const CATEGORIES = [
  'CURRENT_ASSET', 'NON_CURRENT_ASSET', 'CURRENT_LIABILITY', 'NON_CURRENT_LIABILITY',
  'EQUITY', 'COST', 'OPERATING_REVENUE', 'OTHER_INCOME', 'OPERATING_COST_AND_TAX',
  'OTHER_EXPENSE', 'PERIOD_EXPENSE', 'INCOME_TAX', 'PRIOR_YEAR_ADJUSTMENT',
] as const

export function accountAmount(side: 'DEBIT' | 'CREDIT', accounts: AccountReference[], basis?: AmountBasis): AccountAmountExpression {
  return { type: 'ACCOUNT_AMOUNT', operation: 'ACCOUNT_BALANCE', side, accounts, ...(basis ? { basis } : {}) }
}

export function cashFlowItemAmount(direction: CashFlowDirection, itemCodes: string[], cashAccounts: AccountReference[]): CashFlowItemAmountExpression {
  return { type: 'CASH_FLOW_ITEM_AMOUNT', direction, itemCodes, cashAccounts }
}

export function combination(components: LineComponent[]): LinearCombinationExpression {
  return { type: 'LINEAR_COMBINATION', components }
}

export function standardReference(key: string): AccountReference {
  return { type: 'STANDARD_ACCOUNT_KEY', value: key }
}

export function accountReference(accountId: string): AccountReference {
  return { type: 'ACCOUNT_ID', value: accountId }
}

/** All item codes referenced by CASH_FLOW_ITEM_AMOUNT expressions in the definition. */
export function cashFlowItemCodes(definition: FormulaDefinition): string[] {
  const codes = new Set<string>()
  for (const group of definition.groups) {
    for (const line of group.lines) {
      if (line.expression.type === 'CASH_FLOW_ITEM_AMOUNT') {
        line.expression.itemCodes.forEach((code) => codes.add(code))
      }
    }
  }
  return [...codes]
}

/**
 * All account references treated as cash by the definition: the union of
 * `cashAccounts` from cash-flow expressions and the accounts of balance
 * expressions (mirrors the backend's voucher-side contract).
 */
export function cashFlowAccountReferences(definition: FormulaDefinition): AccountReference[] {
  const references: AccountReference[] = []
  const seen = new Set<string>()
  for (const group of definition.groups) {
    for (const line of group.lines) {
      const refs = line.expression.type === 'CASH_FLOW_ITEM_AMOUNT'
        ? line.expression.cashAccounts
        : line.expression.type === 'ACCOUNT_AMOUNT' ? line.expression.accounts : []
      for (const reference of refs) {
        const key = `${reference.type}:${reference.value}`
        if (!seen.has(key)) {
          seen.add(key)
          references.push(reference)
        }
      }
    }
  }
  return references
}

export function definitionFromJson(json: unknown): FormulaDefinition {
  return json as FormulaDefinition
}

export function expressionFromJson(json: unknown): LineExpression {
  return json as LineExpression
}

export function expressionToJson(expression: LineExpression): unknown {
  return expression
}

export function allLines(definition: FormulaDefinition): FormulaLine[] {
  return definition.groups.flatMap((group) => group.lines)
}

/** Lines evaluated before the given line (for the previous-line picker). */
export function previousLines(definition: FormulaDefinition, lineKey: string): FormulaLine[] {
  const lines = allLines(definition)
  const index = lines.findIndex((line) => line.key === lineKey)
  return index < 0 ? [] : lines.slice(0, index)
}

export function referenceLabel(reference: AccountReference, accounts: Account[]): string {
  if (reference.type === 'STANDARD_ACCOUNT_KEY') return reference.value
  const account = accounts.find((candidate) => candidate.id === reference.value)
  return account ? `${account.code} ${account.name}` : reference.value
}

export function accountOptions(accounts: Account[]): { value: string; label: string; isLeaf: boolean }[] {
  return accounts
    .filter((account) => account.status === 'ACTIVE' || true)
    .map((account) => ({
      value: account.id,
      label: `${account.code} ${account.name}${account.isLeaf ? '' : '（包含下级）'}`,
      isLeaf: account.isLeaf,
    }))
}

export function standardKeyOptions(accounts: Account[]): string[] {
  return [...new Set(accounts.map((account) => account.standardAccountKey).filter((key): key is string => Boolean(key)))].sort()
}
