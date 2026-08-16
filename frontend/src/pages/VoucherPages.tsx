import { Alert, App as AntApp, Button, Card, DatePicker, Empty, Form, Input, Modal, Select, Space, Spin, Table, Typography } from 'antd'
import type { FormInstance } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { apiData, apiHeaders, createIdempotencyKey, openApiClient, ApiError } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { clearWorkspaceTabDirty, setWorkspaceTabDirty } from '../components/workspaceDirty'
import { useWorkspaceTabs } from '../components/workspaceTabs'
import { voucherTotals } from '../features/vouchers/money'
import { cashFlowAccountReferences, cashFlowItemCodes, definitionFromJson } from '../features/reportFormulas/types'

export { VoucherListPage } from './VoucherListPage'

type Account = components['schemas']['Account']
type DimensionType = components['schemas']['DimensionType']
type DimensionValue = components['schemas']['LedgerDimensionValue']
type Period = components['schemas']['Period']
type Voucher = components['schemas']['Voucher']
type VoucherCreateRequest = components['schemas']['VoucherCreateRequest']
type VoucherUpdateRequest = components['schemas']['VoucherUpdateRequest']
type CashFlowItem = components['schemas']['LedgerCashFlowItem']

type VoucherForm = { periodId?: string; voucherDate: Dayjs; voucherType: string; voucherNumber?: string; summary?: string; lines: Array<{ accountId?: string; side: 'DEBIT' | 'CREDIT'; currency: string; originalAmount: string; exchangeRate: string; summary?: string; cashFlowItemId?: string; quantity?: string; unitPrice?: string; dimensionValues?: Record<string, string> }> }
const emptyLines: VoucherForm['lines'] = []
const blankLine = (): VoucherForm['lines'][number] => ({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' })
const fiveBlankLines = () => Array.from({ length: 5 }, blankLine)
export const voucherAmountPattern = /^-?\d+(?:\.\d+)?$/
type PeriodDateRange = Pick<Period, 'startDate' | 'endDate' | 'status'>

export interface CashFlowLineValidation {
  lineIndex: number
  message: string
}

export class CashFlowValidationError extends Error {
  readonly lines: CashFlowLineValidation[]

  constructor(lines: CashFlowLineValidation[]) {
    super(lines.map((line) => line.message).join('；'))
    this.name = 'CashFlowValidationError'
    this.lines = lines
  }
}

/**
 * Frontend mirror of the backend cash-flow classification constraint. Cash lines are
 * lines whose account belongs to the published formula's cash account set (or is
 * flagged `cashFlowRequired`). Pure cash internal transfers stay optional; a compound
 * voucher with non-cash lines requires every cash line to carry an active item
 * referenced by the published CASH_FLOW formula.
 *
 * Pass `reportableItems: null` when the published formula is unavailable so the
 * required-check is skipped (the backend then decides).
 */
export function validateCashFlowLines(
  lines: VoucherForm['lines'],
  isCashAccount: (accountId?: string) => boolean,
  reportableItems: CashFlowItem[] | null,
): CashFlowLineValidation[] {
  if (!reportableItems) return []
  const cashIndexes = lines
    .map((line, index) => ({ line, index }))
    .filter(({ line }) => isCashAccount(line.accountId))
  if (cashIndexes.length === 0) return []
  const hasNonCashLine = lines.some((line) => Boolean(line.accountId) && !isCashAccount(line.accountId))
  if (!hasNonCashLine) return []
  const reportableIds = new Set(reportableItems.map((item) => item.id))
  const errors: CashFlowLineValidation[] = []
  for (const { line, index } of cashIndexes) {
    if (!line.cashFlowItemId) {
      errors.push({ lineIndex: index, message: `第 ${index + 1} 条分录的现金收支必须选择现金流项目` })
    } else if (!reportableIds.has(line.cashFlowItemId)) {
      errors.push({ lineIndex: index, message: `第 ${index + 1} 条分录使用的现金流项目不在当前报表公式中（或已停用）` })
    }
  }
  return errors
}

export const dateBelongsToPeriod = (period: PeriodDateRange | undefined, date: Dayjs | undefined) => {
  const value = date?.format('YYYY-MM-DD')
  return Boolean(value && period && period.startDate <= value && value <= period.endDate)
}
export const openPeriodForDate = <T extends PeriodDateRange>(periods: T[], date: Dayjs | undefined) => {
  return periods.find((period) => period.status === 'OPEN' && dateBelongsToPeriod(period, date))
}
export function buildVoucherRequestBody(value: VoucherForm, existingVoucher: false): VoucherCreateRequest
export function buildVoucherRequestBody(value: VoucherForm, existingVoucher: true, expectedVersion: number): VoucherUpdateRequest
export function buildVoucherRequestBody(value: VoucherForm, existingVoucher: boolean, expectedVersion?: number): VoucherCreateRequest | VoucherUpdateRequest {
  const common = {
    voucherDate: value.voucherDate.format('YYYY-MM-DD'),
    voucherType: value.voucherType,
    summary: value.summary,
    lines: value.lines.map(({ dimensionValues, ...line }) => ({
    ...line,
    originalAmount: String(line.originalAmount),
    exchangeRate: String(line.exchangeRate),
    dimensions: Object.entries(dimensionValues || {})
      .filter(([, dimensionValueId]) => Boolean(dimensionValueId))
      .map(([dimensionTypeId, dimensionValueId]) => ({ dimensionTypeId, dimensionValueId })),
    })),
  }
  if (!existingVoucher) return common
  if (expectedVersion === undefined || !value.periodId || !value.voucherNumber) {
    throw new Error('已保存凭证缺少版本、会计期间或凭证号。')
  }
  return { ...common, expectedVersion, periodId: value.periodId, voucherNumber: value.voucherNumber }
}

function VoucherAmountCell({ form, fieldName, side, onChange }: {
  form: FormInstance<VoucherForm>
  fieldName: number
  side: 'DEBIT' | 'CREDIT'
  onChange: () => void
}) {
  const activeSide = Form.useWatch(['lines', fieldName, 'side'], form)
  const amount = Form.useWatch(['lines', fieldName, 'originalAmount'], form)
  const sideLabel = side === 'DEBIT' ? '借方' : '贷方'
  const visibleAmount = activeSide === side && amount && Number(amount) !== 0 ? amount : ''
  return <Input className="voucher-amount-input" inputMode="decimal" value={visibleAmount}
    aria-label={`第 ${fieldName + 1} 条分录${sideLabel}金额`}
    onChange={(event) => {
      const lines = [...(form.getFieldValue('lines') || [])]
      lines[fieldName] = { ...lines[fieldName], side, originalAmount: event.target.value }
      form.setFieldsValue({ lines })
      onChange()
    }} />
}

function VoucherDimensionFields({ form, fieldName, accountsById, dimensionTypesById, dimensionValuesByType }: {
  form: FormInstance<VoucherForm>
  fieldName: number
  accountsById: Map<string, Account>
  dimensionTypesById: Map<string, DimensionType>
  dimensionValuesByType: Map<string, DimensionValue[]>
}) {
  const accountId = Form.useWatch(['lines', fieldName, 'accountId'], form)
  const requirements = accountsById.get(accountId ?? '')?.dimensionRequirements || []

  if (requirements.length === 0) return <Typography.Text type="secondary">—</Typography.Text>

  return <Space direction="vertical" size={4} style={{ width: '100%' }}>
    {requirements.map((requirement) => {
      const dimensionType = dimensionTypesById.get(requirement.dimensionTypeId)
      const name = dimensionType?.name || requirement.name
      const label = `${name}${requirement.required ? '（必填）' : ''}`
      return <Form.Item
        key={requirement.dimensionTypeId}
        name={[fieldName, 'dimensionValues', requirement.dimensionTypeId]}
        label={label}
        required={requirement.required}
        rules={requirement.required ? [{ required: true, message: `请选择${name}` }] : undefined}
      >
        <Select
          allowClear
          showSearch
          optionFilterProp="label"
          aria-label={`第 ${fieldName + 1} 条分录${label}`}
          options={(dimensionValuesByType.get(requirement.dimensionTypeId) || [])
            .filter((value) => value.status === 'ACTIVE')
            .map((value) => ({ value: value.id, label: `${value.code} ${value.name}` }))}
        />
      </Form.Item>
    })}
  </Space>
}

function VoucherCashFlowItemCell({ form, fieldName, accountsById, lines, reportableItems, allItems, isCashAccount }: {
  form: FormInstance<VoucherForm>
  fieldName: number
  accountsById: Map<string, Account>
  lines: VoucherForm['lines']
  reportableItems: CashFlowItem[]
  allItems: CashFlowItem[]
  isCashAccount: (accountId?: string) => boolean
}) {
  const accountId = Form.useWatch(['lines', fieldName, 'accountId'], form)
  const currentItemId = Form.useWatch(['lines', fieldName, 'cashFlowItemId'], form)
  const account = accountId ? accountsById.get(accountId) : undefined
  const isCash = isCashAccount(accountId)
  // Auto-fill the account's default item once the item catalogue is available and the
  // default is still active and referenced by the published formula.
  useEffect(() => {
    if (!isCash || reportableItems.length === 0) return
    const defaultItemId = account?.defaultCashFlowItemId
    if (defaultItemId && reportableItems.some((item) => item.id === defaultItemId)
      && !form.getFieldValue(['lines', fieldName, 'cashFlowItemId'])) {
      form.setFieldValue(['lines', fieldName, 'cashFlowItemId'], defaultItemId)
    }
  }, [account, fieldName, form, isCash, reportableItems])
  if (!isCash) return <Typography.Text type="secondary">—</Typography.Text>
  const hasNonCashLine = lines.some((line, index) =>
    index !== fieldName && Boolean(line.accountId) && !isCashAccount(line.accountId))
  const required = hasNonCashLine
  const options = reportableItems.map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))
  // Keep a stale persisted item visible (clearly marked) instead of showing a raw id.
  if (currentItemId && !reportableItems.some((item) => item.id === currentItemId)) {
    const stale = allItems.find((item) => item.id === currentItemId)
    options.push({
      value: currentItemId,
      label: stale ? `${stale.code} ${stale.name}（已停用或不在公式中）` : `${currentItemId}（已失效）`,
    })
  }
  return <Form.Item
    name={[fieldName, 'cashFlowItemId']}
    rules={required ? [{ required: true, message: '请选择现金流项目' }] : undefined}
  >
    <Select
      allowClear
      showSearch
      optionFilterProp="label"
      aria-label={`第 ${fieldName + 1} 条分录现金流项目`}
      placeholder={required ? '选择现金流项目（必选）' : '内部划转，可不选'}
      options={options}
    />
  </Form.Item>
}

export function VoucherEditorPage() {
  const { ledgerId = '', voucherId } = useParams(); const { session } = useAuth(); const client = useQueryClient(); const navigate = useNavigate(); const { modal, message } = AntApp.useApp(); const { closeTab } = useWorkspaceTabs(); const [form] = Form.useForm<VoucherForm>(); const [commentAction, setCommentAction] = useState<'approve' | 'reject' | null>(null); const [pendingAction, setPendingAction] = useState<string | null>(null)
  const [savedVoucher, setSavedVoucher] = useState<Voucher | null>(null)
  const tabId = voucherId === 'new' ? 'voucher-new' : `voucher-${voucherId}`; const [dirty, setDirty] = useState(false)
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { headers: apiHeaders(session!), params: { path: { ledgerId } } })), enabled: Boolean(session && ledgerId) })
  const dimensionTypes = useQuery({ queryKey: ['dimension-types', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types', { headers: apiHeaders(session!), params: { path: { ledgerId } } })), enabled: Boolean(session && ledgerId) })
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/periods', { headers: apiHeaders(session!), params: { path: { ledgerId } } })), enabled: Boolean(session && ledgerId) })
  const voucher = useQuery({ queryKey: ['voucher', ledgerId, voucherId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/vouchers/{voucherId}', { headers: apiHeaders(session!), params: { path: { ledgerId, voucherId: voucherId! } } })), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const cashFlowItems = useQuery({ queryKey: ['cash-flow-items', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/cash-flow-items', { headers: apiHeaders(session!), params: { path: { ledgerId } } })), enabled: Boolean(session && ledgerId) })
  const cashFlowFormula = useQuery({ queryKey: ['report-formula', ledgerId, 'CASH_FLOW'], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/report-formulas/{code}', { headers: apiHeaders(session!), params: { path: { ledgerId, code: 'CASH_FLOW' } } })), enabled: Boolean(session && ledgerId) })
  const accountsById = useMemo(() => new Map((accounts.data || []).map((account) => [account.id, account])), [accounts.data])
  const reportableCashFlowItemCodes = useMemo(() => {
    const definition = cashFlowFormula.data?.publishedDefinition
    return definition ? new Set(cashFlowItemCodes(definitionFromJson(definition))) : new Set<string>()
  }, [cashFlowFormula.data])
  const reportableCashFlowItems = useMemo(() => (cashFlowItems.data || [])
    .filter((item) => item.status === 'ACTIVE' && reportableCashFlowItemCodes.has(item.code)), [cashFlowItems.data, reportableCashFlowItemCodes])
  const cashFlowPolicyReady = cashFlowFormula.isSuccess && !cashFlowFormula.isError
  // Cash accounts come from the published formula (standard keys expanded to leaf
  // accounts plus concrete ids), matching the backend voucher-side contract; the
  // legacy `cashFlowRequired` account flag is honoured as an additional signal.
  const cashAccountIds = useMemo(() => {
    const definition = cashFlowFormula.data?.publishedDefinition
    if (!definition) return new Set<string>()
    const ids = new Set<string>()
    for (const reference of cashFlowAccountReferences(definitionFromJson(definition))) {
      if (reference.type === 'ACCOUNT_ID') {
        ids.add(reference.value)
      } else {
        for (const account of accounts.data || []) {
          if (account.standardAccountKey === reference.value) ids.add(account.id)
        }
      }
    }
    return ids
  }, [accounts.data, cashFlowFormula.data])
  const isCashAccount = (accountId?: string) => Boolean(accountId)
    && (accountsById.get(accountId!)?.cashFlowRequired || cashAccountIds.has(accountId!))
  const watchedLines = Form.useWatch('lines', form)
  const lines = watchedLines ?? emptyLines
  const dimensionTypesById = useMemo(() => new Map((dimensionTypes.data || []).map((type) => [type.id, type])), [dimensionTypes.data])
  const requiredDimensionTypeIds = useMemo(() => Array.from(new Set(lines.flatMap((line) => (
    accountsById.get(line.accountId || '')?.dimensionRequirements.map((requirement) => requirement.dimensionTypeId) || []
  )))).sort(), [accountsById, lines])
  const dimensionValues = useQuery({
    queryKey: ['dimension-values', ledgerId, requiredDimensionTypeIds],
    queryFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/dimension-values:batch', {
      headers: apiHeaders(session!),
      params: { path: { ledgerId } },
      body: { dimensionTypeIds: requiredDimensionTypeIds },
    })),
    enabled: Boolean(session && ledgerId && requiredDimensionTypeIds.length),
  })
  const dimensionValuesByType = new Map<string, DimensionValue[]>(
    (dimensionValues.data?.groups || []).map((group) => [group.dimensionTypeId, group.values]),
  )
  const totals = useMemo(() => voucherTotals(lines), [lines])
  const hasDebitAmount = lines.some((line) => line.side === 'DEBIT' && Boolean(line.originalAmount) && Number(line.originalAmount) !== 0)
  const hasCreditAmount = lines.some((line) => line.side === 'CREDIT' && Boolean(line.originalAmount) && Number(line.originalAmount) !== 0)
  const persistedVoucher = voucher.data || savedVoucher
  const status = persistedVoucher?.status
  const voucherPeriod = periods.data?.find((period) => period.id === persistedVoucher?.periodId)
  const closedPeriod = Boolean(persistedVoucher) && voucherPeriod?.status === 'CLOSED'
  const editable = !persistedVoucher || voucherPeriod?.status === 'OPEN'
  const save = useMutation({
    mutationFn: async (value: VoucherForm) => {
      const validation = validateCashFlowLines(value.lines, isCashAccount, cashFlowPolicyReady ? reportableCashFlowItems : null)
      if (validation.length > 0) throw new CashFlowValidationError(validation)
      const targetVoucher = persistedVoucher
      const targetVoucherId = targetVoucher?.id
      const openPeriod = targetVoucher ? undefined : openPeriodForDate(periods.data || [], value.voucherDate)
      if (!targetVoucher && !openPeriod) {
        throw new Error('凭证日期不属于任何开放会计期间，请选择未结账期间内的日期。')
      }
      if (targetVoucher && !dateBelongsToPeriod(voucherPeriod, value.voucherDate)) {
        throw new Error(`凭证保存后不能修改会计期间，日期必须保留在 ${voucherPeriod?.periodCode || '原会计期间'}。`)
      }
      const requestValue = targetVoucher
        ? { ...value, periodId: targetVoucher.periodId, voucherNumber: targetVoucher.voucherNumber ?? undefined, voucherType: targetVoucher.voucherType }
        : value
      if (targetVoucher && targetVoucherId) {
        const body = buildVoucherRequestBody(requestValue, true, targetVoucher.version)
        return apiData(openApiClient.PUT('/v1/ledgers/{ledgerId}/vouchers/{voucherId}', { headers: apiHeaders(session!), params: { path: { ledgerId, voucherId: targetVoucherId } }, body }))
      }
      const body = buildVoucherRequestBody(requestValue, false)
      return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers', { headers: { ...apiHeaders(session!), 'Idempotency-Key': createIdempotencyKey() }, params: { path: { ledgerId } }, body }))
    },
    onSuccess: (value) => {
      const wasExisting = voucherId !== 'new' || savedVoucher !== null
      if (voucherId === 'new') setSavedVoucher(value)
      setDirty(false)
      clearWorkspaceTabDirty(tabId)
      client.setQueryData(['voucher', ledgerId, value.id], value)
      void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] })
      message.success(wasExisting ? '凭证修改成功' : '凭证保存成功')
    },
    onError: (error) => {
      if (error instanceof CashFlowValidationError) {
        modal.error({ title: '现金流项目未分类', content: <ul style={{ margin: 0, paddingLeft: 20 }}>{error.lines.map((line) => <li key={line.lineIndex}>{line.message}</li>)}</ul> })
        return
      }
      message.error(error instanceof ApiError && error.problem.code === 'VOUCHER_NOT_BALANCED'
        ? '借贷金额不平衡，凭证未保存。'
        : error instanceof ApiError && error.problem.code === 'VOUCHER_PERIOD_IMMUTABLE'
          ? '凭证保存后不能修改会计期间。'
          : error instanceof ApiError && (error.problem.code === 'CASH_FLOW_CLASSIFICATION_REQUIRED' || error.problem.code === 'CASH_FLOW_ITEM_NOT_REPORTABLE')
            ? '现金流项目不符合当前公式要求：' + error.message
            : error instanceof ApiError || error instanceof Error ? error.message : '凭证保存失败，请稍后重试。')
    },
  })
  const action = async (name: 'validate' | 'submit' | 'approve' | 'reject' | 'post', body?: { comment: string }): Promise<boolean> => {
    const validation = validateCashFlowLines(form.getFieldValue('lines') || [], isCashAccount, cashFlowPolicyReady ? reportableCashFlowItems : null)
    if (validation.length > 0) {
      modal.error({ title: '现金流项目未分类', content: <ul style={{ margin: 0, paddingLeft: 20 }}>{validation.map((line) => <li key={line.lineIndex}>{line.message}</li>)}</ul> })
      return false
    }
    if (!voucherId || voucherId === 'new' || pendingAction) return false; setPendingAction(name); try { const request = { headers: apiHeaders(session!), params: { path: { ledgerId, voucherId } } }; if (name === 'validate') await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:validate', request)); if (name === 'submit') await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:submit', request)); if (name === 'approve') await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:approve', { ...request, body: body! })); if (name === 'reject') await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:reject', { ...request, body: body! })); if (name === 'post') await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:post', request)); await voucher.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); return true } catch (error) { modal.error(error instanceof ApiError && error.problem.code === 'RESOURCE_VERSION_CONFLICT' ? { title: '凭证已被其他人修改', content: '请刷新后确认是否放弃本地修改。' } : error instanceof ApiError && (error.problem.code === 'CASH_FLOW_CLASSIFICATION_REQUIRED' || error.problem.code === 'CASH_FLOW_ITEM_NOT_REPORTABLE') ? { title: '现金流项目不符合要求', content: error.message } : { title: '凭证操作失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); return false } finally { setPendingAction(null) } }
  const removeVoucher = async () => { if (!voucherId || voucherId === 'new' || pendingAction) return; setPendingAction('delete'); try { await apiData(openApiClient.DELETE('/v1/ledgers/{ledgerId}/vouchers/{voucherId}', { headers: apiHeaders(session!), params: { path: { ledgerId, voucherId } } })); clearWorkspaceTabDirty(tabId); client.removeQueries({ queryKey: ['voucher', ledgerId, voucherId], exact: true }); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); message.success('凭证删除成功'); closeTab(tabId, { discardChanges: true }); navigate(`/ledgers/${ledgerId}/vouchers`) } catch (error) { message.error(error instanceof ApiError ? error.message : '删除凭证失败，请稍后重试。'); throw error } finally { setPendingAction(null) } }
  const initial = voucher.data ? { ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } : { voucherDate: dayjs(), voucherType: '记', lines: fiveBlankLines() }
  useEffect(() => { if (voucher.data) form.setFieldsValue({ ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } as VoucherForm) }, [voucher.data, form])
  useEffect(() => { setWorkspaceTabDirty(tabId, dirty); if (!dirty) return; const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault() }; window.addEventListener('beforeunload', beforeUnload); return () => window.removeEventListener('beforeunload', beforeUnload) }, [dirty, tabId])
  useEffect(() => () => clearWorkspaceTabDirty(tabId), [tabId])
  if (voucher.isError) return <Alert type="error" message="凭证读取失败" />
  if (voucherId !== 'new' && (voucher.isLoading || periods.isLoading)) return <Space role="status"><Spin /><Typography.Text>正在读取凭证会计期间</Typography.Text></Space>
  if (persistedVoucher && (periods.isError || !voucherPeriod)) return <Alert type="error" message="凭证所属会计期间读取失败，当前凭证不可编辑" />
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="voucher-page-toolbar"><Typography.Text type="secondary">{persistedVoucher?.status || '新凭证'}</Typography.Text><Button onClick={() => navigate(`/ledgers/${ledgerId}/vouchers`)}>返回列表</Button></div>
    <Card className={`voucher-editor-card${closedPeriod ? ' voucher-editor-card-closed' : ''}`}>
      {closedPeriod && <div className="voucher-closed-stamp" role="status">已结账</div>}
      <div inert={!editable ? true : undefined} aria-label={!editable ? '已结账凭证，只读' : undefined}>
      <Form className="voucher-editor-form" form={form} layout="vertical" initialValues={initial} onValuesChange={() => setDirty(true)} onFinish={(value) => save.mutate(value as VoucherForm)}>
      <div className="voucher-document-header">
        <div className="voucher-header-fields">
          <Form.Item name="voucherType" hidden><Input /></Form.Item><Form.Item name="voucherNumber" hidden><Input /></Form.Item><Form.Item name="periodId" hidden><Input /></Form.Item>
          {persistedVoucher ? <Typography.Text className="voucher-number-display">凭证号 {persistedVoucher.voucherType}-{persistedVoucher.voucherNumber}</Typography.Text> : <Typography.Text className="voucher-number-pending">凭证号由系统保存时自动生成</Typography.Text>}
        </div>
        <div className="voucher-document-title">
          <Typography.Title level={1}>记账凭证</Typography.Title>
          {persistedVoucher
            ? <Typography.Text>会计期间 {voucherPeriod?.periodCode || '—'}（保存后不可修改）</Typography.Text>
            : <Typography.Text type="secondary">会计期间由凭证日期自动确定</Typography.Text>}
        </div>
        <div className="voucher-header-fields voucher-header-fields-right">
          <Form.Item name="voucherDate" label="日期" rules={[{ required: true }]}><DatePicker aria-label="凭证日期" disabledDate={(date) => persistedVoucher ? !dateBelongsToPeriod(voucherPeriod, date) : !openPeriodForDate(periods.data || [], date)} /></Form.Item>
        </div>
      </div>
      <Form.List name="lines">{(fields, { add, remove }) => <>
        <Table className="voucher-entry-table" bordered size="middle" pagination={false} scroll={{ x: 1400 }} rowKey="key" dataSource={fields}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分录" /> }}
          columns={[
            { title: '操作', width: 118, align: 'center', render: (_, field) => <Space size={0} className="voucher-line-actions"><Button type="link" icon={<PlusOutlined />} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' }, field.name)}>插入</Button><Button type="text" danger icon={<DeleteOutlined />} aria-label={`删除第 ${field.name + 1} 条分录`} onClick={() => remove(field.name)} /></Space> },
            { title: '摘要', width: 240, render: (_, field) => <><Form.Item name={[field.name, 'side']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'currency']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'exchangeRate']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'originalAmount']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'summary']}><Input aria-label={`第 ${field.name + 1} 条分录摘要`} /></Form.Item></> },
            { title: '会计科目', width: 330, render: (_, field) => <Form.Item name={[field.name, 'accountId']}><Select showSearch optionFilterProp="label" aria-label={`第 ${field.name + 1} 条分录会计科目`} onChange={(accountId) => {
              const allowedDimensionTypeIds = new Set(accountsById.get(accountId)?.dimensionRequirements.map((requirement) => requirement.dimensionTypeId) || [])
              const currentDimensionValues = form.getFieldValue(['lines', field.name, 'dimensionValues']) || {}
              form.setFieldValue(['lines', field.name, 'dimensionValues'], Object.fromEntries(
                Object.entries(currentDimensionValues).filter(([dimensionTypeId]) => allowedDimensionTypeIds.has(dimensionTypeId)),
              ))
              // Reset the cash-flow item on account change; the item cell auto-fills the
              // new account's default once the reportable item catalogue is available.
              form.setFieldValue(['lines', field.name, 'cashFlowItemId'], undefined)
            }} options={(accounts.data || []).filter((account) => account.status === 'ACTIVE' && account.isLeaf).map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item> },
            { title: '辅助核算', width: 280, render: (_, field) => <VoucherDimensionFields form={form} fieldName={field.name} accountsById={accountsById} dimensionTypesById={dimensionTypesById} dimensionValuesByType={dimensionValuesByType} /> },
            { title: '现金流项目', width: 210, render: (_, field) => <VoucherCashFlowItemCell form={form} fieldName={field.name} accountsById={accountsById} lines={lines} reportableItems={reportableCashFlowItems} allItems={cashFlowItems.data || []} isCashAccount={isCashAccount} /> },
            { title: '借方金额', width: 150, align: 'right', className: 'voucher-amount-column voucher-debit-column', render: (_, field) => <VoucherAmountCell form={form} fieldName={field.name} side="DEBIT" onChange={() => setDirty(true)} /> },
            { title: '贷方金额', width: 150, align: 'right', className: 'voucher-amount-column voucher-credit-column', render: (_, field) => <VoucherAmountCell form={form} fieldName={field.name} side="CREDIT" onChange={() => setDirty(true)} /> },
          ]}
          summary={() => <Table.Summary fixed><Table.Summary.Row className="voucher-total-row">
            <Table.Summary.Cell index={0} colSpan={5}><span>合计（本位币）</span></Table.Summary.Cell>
            <Table.Summary.Cell index={5} className="voucher-total-amount">{hasDebitAmount ? totals.debit.toFixed(2) : ''}</Table.Summary.Cell>
            <Table.Summary.Cell index={6} className="voucher-total-amount">{hasCreditAmount ? totals.credit.toFixed(2) : ''}</Table.Summary.Cell>
          </Table.Summary.Row></Table.Summary>}
        />
        <Button className="voucher-add-line" icon={<PlusOutlined />} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' })}>新增分录</Button>
      </> }</Form.List>
      <Space>{editable && <Button type="primary" htmlType="submit" loading={save.isPending} disabled={Boolean(pendingAction)}>{persistedVoucher ? '保存修改' : '保存并记账'}</Button>}{voucherId && voucherId !== 'new' && editable && <>{status === 'DRAFT' && <Button loading={pendingAction === 'validate'} disabled={Boolean(pendingAction)} onClick={() => void action('validate')}>校验</Button>}{status === 'VALIDATED' && (voucher.data?.approvalRequired ? <Button loading={pendingAction === 'submit'} disabled={Boolean(pendingAction)} onClick={() => void action('submit')}>提交</Button> : <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>)}{status === 'SUBMITTED' && <><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('approve')}>审批</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('reject')}>退回</Button></>}{status === 'APPROVED' && <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>}<Button danger loading={pendingAction === 'delete'} disabled={Boolean(pendingAction)} onClick={() => modal.confirm({ title: '删除凭证？', content: '删除后会同步冲减余额投影。', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消', onOk: removeVoucher })}>删除凭证</Button></>}</Space>
    </Form></div></Card>
    <Modal open={Boolean(commentAction)} title="填写原因" okText="确认" cancelText="取消" confirmLoading={Boolean(pendingAction)} onCancel={() => setCommentAction(null)} onOk={async () => { const value = (document.querySelector('#voucher-action-comment') as HTMLInputElement)?.value; if (value && await action(commentAction || 'approve', { comment: value })) setCommentAction(null) }}><Input id="voucher-action-comment" placeholder="原因不能为空" /></Modal>
  </Space>
}
