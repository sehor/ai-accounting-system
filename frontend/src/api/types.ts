export type LedgerRole = 'OWNER' | 'EDITOR' | 'REVIEWER' | 'VIEWER' | 'AGENT'
export type MembershipStatus = 'ACTIVE' | 'INACTIVE'

export interface User {
  id: string
  issuer: string
  subject: string
  displayName: string | null
  email: string | null
  status: string
}

export interface Ledger {
  id: string
  name: string
  accountingStandardCode: string
  accountingStandardVersion: string
  baseCurrency: string
  startDate: string
  approvalEnabled: boolean
  status: string
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
}

export interface Period {
  id: string
  ledgerId: string
  periodCode: string
  startDate: string
  endDate: string
  status: 'OPEN' | 'CLOSED' | string
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
