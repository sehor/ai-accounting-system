export type LedgerRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER' | 'AGENT'
export type MembershipStatus = 'ACTIVE' | 'INACTIVE'
export type UserType = 'HUMAN' | 'AGENT'

export interface User {
  id: string
  issuer: string
  subject: string
  displayName: string | null
  email: string | null
  userType: UserType
  status: string
}

export interface AdminUser extends User {
  deleted: boolean
  protectedUser: boolean
}

export interface Ledger {
  id: string
  name: string
  description: string
  accountingStandardCode: string
  accountingStandardVersion: string
  baseCurrency: string
  startDate: string
  approvalEnabled: boolean
  status: string
}

export interface AdminLedger extends Ledger {
  deleted: boolean
}

export interface Member {
  userId: string
  role: LedgerRole
  status: MembershipStatus
  displayName: string | null
  email: string | null
}

export interface Account {
  id: string
  ledgerId: string
  code: string
  name: string
  category: string
  normalBalance: string
  status: string
  parentId: string | null
  level: number
  isLeaf: boolean
  isTemplate: boolean
  hasBusinessUsage: boolean
  coreLocked: boolean
  legacyCode: boolean
  version: number
  cashFlowRequired: boolean
  defaultCashFlowItemId: string | null
  quantityEnabled: boolean
  unitName: string | null
  dimensionRequirements: AccountDimensionRequirement[]
  createdAt: string | null
}

export interface AccountDimensionRequirement {
  dimensionTypeId: string
  code: string
  name: string
  required: boolean
}

export interface CashFlowItem {
  id: string
  ledgerId: string
  code: string
  name: string
  status: string
  isTemplate: boolean
}

export interface AccountingStandard {
  code: string
  version: string
  name: string
  effectiveDate: string
  accountCodeRule: AccountCodeRule
}

export interface AccountCodeRule {
  level2Width: number
  level3Width: number
  level4Width: number
}

export interface KingdeeImportResult {
  voucherCount: number
  rowCount: number
}

export interface AccountImportRow {
  rowNo: number
  rawData: Record<string, string>
  cleanedData: Record<string, string>
  accountCode: string
  targetAccountId: string | null
  expectedAccountVersion: number | null
  action: 'CREATE' | 'UPDATE' | 'MAP' | 'SKIP' | null
  confirmed: boolean
  confidence: string | null
  issues: string[]
}

export interface AccountImportPreview {
  id: string
  ledgerId: string
  format: 'STANDARD' | 'KINGDEE'
  status: 'PREVIEW' | 'COMMITTED'
  ledgerVersion: number
  filename: string
  rowCount: number
  errorCount: number
  aiStatus: string
  rows: AccountImportRow[]
}

export interface Period {
  id: string
  ledgerId: string
  periodCode: string
  startDate: string
  endDate: string
  status: 'OPEN' | 'CLOSED' | string
  hasVouchers?: boolean
}

export interface DimensionType {
  id: string
  ledgerId: string
  code: string
  name: string
  required: boolean
  status: string
}

export interface DimensionValue {
  id: string
  ledgerId: string
  dimensionTypeId: string
  code: string
  name: string
  status: string
}

export interface OpeningBalance {
  id: string
  ledgerId: string
  periodId: string
  accountId: string
  currency: string
  dimensionKey: string
  debitOriginal: string
  creditOriginal: string
  exchangeRate: string
  debitBase: string
  creditBase: string
  confirmed: boolean
}

export interface VoucherLine {
  id: string
  lineNo: number
  accountId: string
  side: 'DEBIT' | 'CREDIT'
  currency: string
  originalAmount: string
  exchangeRate: string
  baseAmount: string
  summary: string | null
  cashFlowItemId: string | null
  quantity: string | null
  unitPrice: string | null
  dimensions: VoucherLineDimension[]
}

export interface VoucherLineDimension {
  dimensionTypeId: string
  dimensionValueId: string
}

export interface Voucher {
  id: string
  ledgerId: string
  periodId: string
  voucherDate: string
  voucherType: string
  voucherNumber: string
  summary: string | null
  status: string
  approvalRequired: boolean
  version: number
  lines: VoucherLine[]
  sourceType?: string | null
  sourceId?: string | null
}

export interface FixedAssetCategory {
  id: string
  ledgerId: string
  code: string
  name: string
  usefulLifeMonths: number
  residualRate: string
  status: string
  assetAccountId: string
  accumulatedDepreciationAccountId: string
  depreciationExpenseAccountId: string
  impairmentAccountId: string
  clearingAccountId: string
  disposalGainAccountId: string
  disposalLossAccountId: string
  version: number
}

export interface FixedAsset {
  id: string
  ledgerId: string
  categoryId: string
  categoryCode: string
  categoryName: string
  code: string
  name: string
  status: string
  quantity: number
  serviceDate: string
  originalCost: string
  inputTax: string
  usefulLifeMonths: number
  residualRate: string
  residualAmount: string
  openingAccumulatedDepreciation: string
  openingDepreciatedMonths: number
  impairmentAmount: string
  monthlyDepreciation: string
  periodDepreciation: string
  endingAccumulatedDepreciation: string
  openingNetValue: string
  endingNetValue: string
  departmentValueId: string | null
  acquisitionVoucherId: string | null
  assetAccountId: string
  accumulatedDepreciationAccountId: string
  depreciationExpenseAccountId: string
  impairmentAccountId: string
  clearingAccountId: string
  disposalGainAccountId: string
  disposalLossAccountId: string
  disposalDate: string | null
  note: string | null
  version: number
}

export interface FixedAssetPage { data: FixedAsset[]; page: number; pageSize: number; totalItems: number; totalPages: number }
export interface FixedAssetPreviewLine { assetId: string; code: string; name: string; amount: string; status: string; detail: string | null }
export interface FixedAssetPreview {
  periodId: string; periodCode: string; totalAmount: string; eligibleCount: number; completedCount: number; pendingCount: number
  readyToClose: boolean; blockers: string[]; lines: FixedAssetPreviewLine[]
}
export interface FixedAssetRun { id: string; periodId: string; runType: string; status: string; voucherId: string; totalAmount: string; inputFingerprint: string; createdAt: string }
export interface FixedAssetDisposal { id: string; assetId: string; periodId: string; depreciationVoucherId: string | null; transferVoucherId: string; settlementVoucherId: string; carryingAmount: string; gainOrLoss: string }

export type PeriodClosingStepType = 'DEPRECIATION' | 'EXPENSE_TRANSFER' | 'REVENUE_TRANSFER' | 'YEAR_END_PROFIT_TRANSFER'
export type PeriodClosingStepStatus = 'NOT_REQUIRED' | 'PENDING' | 'GENERATED' | 'STALE' | 'BLOCKED'
export interface PeriodClosingBlocker { code: string; title: string; detail: string }
export interface PeriodClosingStep {
  step: PeriodClosingStepType
  status: PeriodClosingStepStatus
  amount: string
  voucherId: string | null
  inputFingerprint: string | null
  blockers: PeriodClosingBlocker[]
  updatedAt: string
}
export interface PeriodClosingTrialBalance {
  openingDebit: string; openingCredit: string; periodDebit: string; periodCredit: string
  closingDebit: string; closingCredit: string
  openingDifference: string; periodDifference: string; closingDifference: string
  balanced: boolean
}
export interface PeriodClosingStatus {
  ledgerId: string; periodId: string; periodCode: string
  steps: PeriodClosingStep[]; blockers: PeriodClosingBlocker[]
  trialBalance: PeriodClosingTrialBalance; canClose: boolean
}
export interface PeriodClosingSettings {
  ledgerId: string; profitAccountId: string | null; retainedEarningsAccountId: string | null
  defaultProfitAccountId: string | null; defaultRetainedEarningsAccountId: string | null; version: number
}

export interface VoucherRevision {
  id: string
  revision: number
  action: string
  actorId: string
  reason: string | null
  beforeData: string | null
  afterData: string | null
  createdAt: string
}

export interface DocumentRecord {
  id: string
  ledgerId: string
  objectKey: string
  fileName: string
  contentType: string
  sizeBytes: number
  sha256: string
  status: string
  duplicateWarning: boolean
  createdAt: string
}

export interface Extraction {
  id: string
  documentId: string
  provider: string
  status: string
  structuredResult: string
}

export interface TrialBalanceLine {
  accountId: string
  code: string
  name: string
  category: string
  openingDebit: string
  openingCredit: string
  periodDebit: string
  periodCredit: string
  closingDebit: string
  closingCredit: string
  debit: string
  credit: string
  balance: string
}

export interface StatementLine {
  code: string
  name: string
  amount: string
}

export interface Statement {
  totalLines: number
  lines: StatementLine[]
}

export interface StatutoryLine {
  key: string
  lineNo: number
  name: string
  indent: number
  rowType: string
  primaryAmount: string | number
  comparativeAmount: string | number
}

export interface StatutoryGroup {
  key: string
  title: string
  lines: StatutoryLine[]
}

export interface StatutoryCheck {
  key: string
  name: string
  passed: boolean
  difference: string | number
}

export interface StatutoryStatement {
  reportType: string
  templateCode: string
  standardCode: string
  standardVersion: string
  periodCode: string
  primaryColumn: string
  comparativeColumn: string
  groups: StatutoryGroup[]
  checks: StatutoryCheck[]
}

export interface LedgerLine {
  voucherId: string
  voucherNumber: string
  voucherDate: string
  accountCode: string
  accountName: string
  side: string
  amount: string
  dimensionKey: string | null
}

export interface Pagination {
  page: number
  pageSize: number
  totalItems: number
  totalPages: number
}

export interface GeneralLedgerAccount {
  accountId: string
  accountCode: string
  accountName: string
  normalBalance: string
  openingDirection: 'DEBIT' | 'CREDIT'
  openingBalance: string
  periodDebit: string
  periodCredit: string
  yearDebit: string
  yearCredit: string
  endingDirection: 'DEBIT' | 'CREDIT'
  endingBalance: string
}

export interface GeneralLedgerPage {
  periodFrom: string
  periodTo: string
  periodCode: string | null
  data: GeneralLedgerAccount[]
  pagination: Pagination
}

export interface SubLedgerEntry {
  voucherId: string
  voucherNumber: string
  voucherDate: string
  postingAccountId: string
  postingAccountCode: string
  postingAccountName: string
  summary: string
  debit: string
  credit: string
  direction: 'DEBIT' | 'CREDIT'
  balance: string
}

export interface SubLedgerPage {
  periodFrom: string
  periodTo: string
  periodCode: string | null
  accountId: string
  accountCode: string
  accountName: string
  openingDirection: 'DEBIT' | 'CREDIT'
  openingBalance: string
  data: SubLedgerEntry[]
  periodDebit: string
  periodCredit: string
  endingDirection: 'DEBIT' | 'CREDIT'
  endingBalance: string
  pagination: Pagination
}

export interface AuditEntry {
  id: string
  aggregateType: string
  aggregateId: string
  revision: number
  action: string
  actorId: string
  reason: string | null
  createdAt: string
}

export interface ProblemDetails {
  title?: string
  detail?: string
  status?: number
  code?: string
  traceId?: string
  retryable?: boolean
}
