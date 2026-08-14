import { Alert, App as AntApp, Button, Card, Checkbox, DatePicker, Empty, Form, Input, Modal, Select, Space, Spin, Table, Tag, Typography, Upload } from 'antd'
import type { FormInstance } from 'antd'
import { DeleteOutlined, DownloadOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { apiFetch, createIdempotencyKey, jsonBody, ApiError } from '../api/client'
import type { Account, KingdeeImportResult, Period, Voucher } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'
import { clearWorkspaceTabDirty, setWorkspaceTabDirty } from '../components/workspaceDirty'
import { useWorkspaceTabs } from '../components/workspaceTabs'
import { voucherTotals } from '../features/vouchers/money'

export { VoucherListPage } from './VoucherListPage'

type VoucherForm = { periodId?: string; voucherDate: Dayjs; voucherType: string; voucherNumber?: string; summary?: string; lines: Array<{ accountId?: string; side: 'DEBIT' | 'CREDIT'; currency: string; originalAmount: string; exchangeRate: string; summary?: string; cashFlowItemId?: string; quantity?: string; unitPrice?: string; dimensionValues?: Record<string, string> }> }
const emptyLines: VoucherForm['lines'] = []
const blankLine = (): VoucherForm['lines'][number] => ({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' })
const fiveBlankLines = () => Array.from({ length: 5 }, blankLine)
export const voucherAmountPattern = /^-?\d+(?:\.\d+)?$/
export const dateBelongsToPeriod = (period: Period | undefined, date: Dayjs | undefined) => {
  const value = date?.format('YYYY-MM-DD')
  return Boolean(value && period && period.startDate <= value && value <= period.endDate)
}
export const openPeriodForDate = (periods: Period[], date: Dayjs | undefined) => {
  return periods.find((period) => period.status === 'OPEN' && dateBelongsToPeriod(period, date))
}
export const buildVoucherRequestBody = (value: VoucherForm, existingVoucher: boolean, expectedVersion?: number) => ({
  ...(existingVoucher ? { expectedVersion, voucherNumber: value.voucherNumber, periodId: value.periodId } : {}),
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
})

export function VoucherListPageLegacy() {
  const { ledgerId = '' } = useParams(); const { session } = useAuth(); const navigate = useNavigate(); const client = useQueryClient(); const { message } = AntApp.useApp(); const [search, setSearch] = useWorkspaceSearchParams()
  const limit = Number(search.get('limit') || 20); const offset = Number(search.get('offset') || 0); const [selectedRowKeys, setSelectedRowKeys] = useState<React.Key[]>([]); const [bulkAction, setBulkAction] = useState<'approve' | 'post' | null>(null); const [bulkComment, setBulkComment] = useState(''); const [exportOpen, setExportOpen] = useState(false); const [mergeEntries, setMergeEntries] = useState(false)
  const query = useQuery({ queryKey: ['vouchers', ledgerId, limit, offset], queryFn: () => apiFetch<Voucher[]>(`/ledgers/${ledgerId}/vouchers?limit=${limit}&offset=${offset}`, session!), enabled: Boolean(session && ledgerId) })
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!), enabled: Boolean(session && ledgerId) })
  const accountById = useMemo(() => new Map((accounts.data || []).map((account) => [account.id, account])), [accounts.data])
  const rows = query.data || []; const selectedRows = rows.filter((row) => selectedRowKeys.includes(row.id)); const reviewableRows = selectedRows.filter((row) => row.status === 'SUBMITTED'); const postableRows = selectedRows.filter((row) => row.status === 'APPROVED' || (row.status === 'VALIDATED' && !row.approvalRequired))
  const importKingdee = useMutation({ mutationFn: (file: File) => { const body = new FormData(); body.append('file', file); return apiFetch<KingdeeImportResult>(`/ledgers/${ledgerId}/data-exchange/kingdee:import`, session!, { method: 'POST', headers: { 'Idempotency-Key': createIdempotencyKey() }, body }) }, onSuccess: (result) => { message.success(`已导入 ${result.voucherCount} 张凭证、${result.rowCount} 条分录`); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }) }, onError: (error) => message.error(error instanceof ApiError ? error.message : '金蝶凭证导入失败') })
  const exportKingdee = useMutation({ mutationFn: (shouldMerge: boolean) => apiFetch<Blob>(`/ledgers/${ledgerId}/data-exchange/kingdee:export?mergeEntries=${shouldMerge}`, session!), onSuccess: (blob) => { setExportOpen(false); const url = URL.createObjectURL(blob); const anchor = document.createElement('a'); anchor.href = url; anchor.download = 'kingdee-vouchers.xlsx'; anchor.click(); URL.revokeObjectURL(url) }, onError: (error) => message.error(error instanceof ApiError ? error.message : '金蝶凭证导出失败') })
  const batch = useMutation({ mutationFn: async ({ action, selected, comment }: { action: 'approve' | 'post'; selected: Voucher[]; comment?: string }) => {
    const results = await Promise.allSettled(selected.map((row) => apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${row.id}:${action}`, session!, { method: 'POST', body: action === 'approve' ? jsonBody({ comment }) : undefined })))
    return { succeeded: results.filter((result) => result.status === 'fulfilled').length, failed: results.filter((result) => result.status === 'rejected').length }
  }, onSuccess: ({ succeeded, failed }) => { setSelectedRowKeys([]); setBulkAction(null); setBulkComment(''); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); if (failed) message.warning(`已完成 ${succeeded} 张，${failed} 张失败`); else message.success(`已完成 ${succeeded} 张凭证`) } })
  const statusLabel = (status: string) => ({ DRAFT: '草稿', VALIDATED: '已校验', SUBMITTED: '待审核', APPROVED: '已审核', POSTED: '已记账', DELETED: '已删除' }[status] || status)
  const statusColor = (status: string) => ({ DRAFT: 'default', VALIDATED: 'blue', SUBMITTED: 'orange', APPROVED: 'cyan', POSTED: 'green', DELETED: 'red' }[status] || 'default')
  const accountLabel = (accountId: string) => { const account = accountById.get(accountId); return account ? `${account.code} ${account.name}` : accountId }
  const runBulkAction = () => { if (!bulkAction) return; const selected = bulkAction === 'approve' ? reviewableRows : postableRows; if (selected.length) batch.mutate({ action: bulkAction, selected, comment: bulkComment.trim() }) }
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>凭证工作台</Typography.Title><Typography.Text type="secondary">保存或导入成功后自动审批并记账。</Typography.Text></div><Space wrap><Upload accept=".xls,.xlsx" showUploadList={false} beforeUpload={(file) => { const name = file.name.toLowerCase(); if ((!name.endsWith('.xls') && !name.endsWith('.xlsx')) || file.size > 10 * 1024 * 1024) { message.error('仅支持不超过 10 MiB 的 .xls/.xlsx 文件'); return Upload.LIST_IGNORE } importKingdee.mutate(file); return false }}><Button icon={<UploadOutlined />} loading={importKingdee.isPending}>导入金蝶凭证</Button></Upload><Button icon={<DownloadOutlined />} loading={exportKingdee.isPending} onClick={() => setExportOpen(true)}>导出金蝶凭证</Button><Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/vouchers/new`)}>新建凭证</Button></Space></div>
    {query.isError && <Alert type="error" showIcon message="凭证列表读取失败" action={<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>重试</Button>} />}
    <Card className="voucher-list-card" extra={selectedRows.length ? <Space><Typography.Text type="secondary">已选 {selectedRows.length} 张</Typography.Text>{reviewableRows.length > 0 && <Button disabled={batch.isPending} onClick={() => { setBulkAction('approve'); setBulkComment('') }}>批量审核</Button>}{postableRows.length > 0 && <Button type="primary" disabled={batch.isPending} loading={batch.isPending && bulkAction === 'post'} onClick={() => { setBulkAction('post'); setBulkComment(''); batch.mutate({ action: 'post', selected: postableRows }) }}>批量记账</Button>}</Space> : null}>
      <Table rowKey="id" className="financial-table" loading={query.isLoading} dataSource={rows} locale={{ emptyText: <Empty description="暂无凭证" /> }} rowSelection={{ selectedRowKeys, onChange: (keys) => setSelectedRowKeys(keys as string[]) }} expandable={{
        defaultExpandAllRows: true,
        expandedRowRender: (row: Voucher) => <VoucherLinesTable row={row} accountLabel={accountLabel} />,
      }} pagination={{ current: Math.floor(offset / limit) + 1, pageSize: limit, total: (offset + rows.length) + (rows.length === limit ? 1 : 0), showSizeChanger: false, onChange: (page) => { setSelectedRowKeys([]); setSearch({ limit: String(limit), offset: String((page - 1) * limit) }) } }} scroll={{ x: 1450 }} columns={[
        { title: '日期', dataIndex: 'voucherDate', width: 110 }, { title: '凭证号', width: 110, render: (_: unknown, row: Voucher) => <Link to={`/ledgers/${ledgerId}/vouchers/${row.id}`}>{row.voucherType}-{row.voucherNumber}</Link> }, { title: '摘要', dataIndex: 'summary', width: 240, ellipsis: true }, { title: '科目概览', width: 300, render: (_: unknown, row: Voucher) => <Space direction="vertical" size={0}>{row.lines.slice(0, 3).map((line) => <Typography.Text key={line.id} ellipsis={{ tooltip: accountLabel(line.accountId) }}>{accountLabel(line.accountId)}</Typography.Text>)}{row.lines.length > 3 && <Typography.Text type="secondary">另 {row.lines.length - 3} 条分录</Typography.Text>}</Space> },
        { title: '借方金额', width: 130, align: 'right' as const, render: (_: unknown, row: Voucher) => voucherTotals(row.lines).debit.toFixed(2) }, { title: '贷方金额', width: 130, align: 'right' as const, render: (_: unknown, row: Voucher) => voucherTotals(row.lines).credit.toFixed(2) }, { title: '分录', width: 70, align: 'center' as const, render: (_: unknown, row: Voucher) => row.lines.length }, { title: '状态', width: 110, render: (value: string) => <Tag color={statusColor(value)}>{statusLabel(value)}</Tag>, dataIndex: 'status' }, { title: '版本', width: 70, dataIndex: 'version' }, { title: '操作', width: 110, fixed: 'right' as const, render: (_: unknown, row: Voucher) => <Link to={`/ledgers/${ledgerId}/vouchers/${row.id}`}>{row.status === 'SUBMITTED' ? '审核处理' : ['DRAFT', 'VALIDATED', 'APPROVED'].includes(row.status) ? '继续处理' : '查看详情'}</Link> },
      ]} />
    </Card>
    <Modal open={bulkAction === 'approve'} title={`批量审核（${reviewableRows.length} 张）`} okText="确认审核" cancelText="取消" confirmLoading={batch.isPending} okButtonProps={{ disabled: !bulkComment.trim() }} onCancel={() => setBulkAction(null)} onOk={runBulkAction}><Input.TextArea rows={3} value={bulkComment} onChange={(event) => setBulkComment(event.target.value)} placeholder="请输入审核意见（必填）" /></Modal>
    <Modal open={exportOpen} title="导出金蝶凭证" okText="导出" cancelText="取消" confirmLoading={exportKingdee.isPending} onCancel={() => setExportOpen(false)} onOk={() => exportKingdee.mutate(mergeEntries)}>
      <Space direction="vertical" size={4}>
        <Checkbox checked={mergeEntries} onChange={(event) => setMergeEntries(event.target.checked)}>合并同类分录</Checkbox>
        <Typography.Text type="secondary">仅合并同月、同银行，且一级科目符合“收款-主营、付款-日常、付款-主营、银行费用”之一的凭证。</Typography.Text>
      </Space>
    </Modal>
  </Space>
}

function VoucherLinesTable({ row, accountLabel }: { row: Voucher; accountLabel: (accountId: string) => string }) {
  return <Table size="small" pagination={false} rowKey="id" dataSource={row.lines} columns={[
    { title: '分录摘要', dataIndex: 'summary', width: 180, render: (value: string | null) => value || row.summary || '—' },
    { title: '科目', width: 260, render: (_: unknown, line: Voucher['lines'][number]) => accountLabel(line.accountId) },
    { title: '方向', width: 80, render: (_: unknown, line: Voucher['lines'][number]) => line.side === 'DEBIT' ? '借' : '贷' },
    { title: '币种/汇率', width: 110, render: (_: unknown, line: Voucher['lines'][number]) => `${line.currency} / ${line.exchangeRate}` },
    { title: '原币金额', dataIndex: 'originalAmount', width: 120, align: 'right' as const },
    { title: '借方金额', width: 120, align: 'right' as const, render: (_: unknown, line: Voucher['lines'][number]) => line.side === 'DEBIT' ? line.baseAmount : '' },
    { title: '贷方金额', width: 120, align: 'right' as const, render: (_: unknown, line: Voucher['lines'][number]) => line.side === 'CREDIT' ? line.baseAmount : '' },
    { title: '控制项', width: 180, render: (_: unknown, line: Voucher['lines'][number]) => [line.cashFlowItemId, line.quantity && `数量 ${line.quantity}`, line.unitPrice && `单价 ${line.unitPrice}`].filter(Boolean).join('；') || '—' },
  ]} />
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

export function VoucherEditorPage() {
  const { ledgerId = '', voucherId } = useParams(); const { session } = useAuth(); const client = useQueryClient(); const navigate = useNavigate(); const { modal, message } = AntApp.useApp(); const { closeTab } = useWorkspaceTabs(); const [form] = Form.useForm<VoucherForm>(); const [commentAction, setCommentAction] = useState<'approve' | 'reject' | null>(null); const [pendingAction, setPendingAction] = useState<string | null>(null)
  const [savedVoucher, setSavedVoucher] = useState<Voucher | null>(null)
  const tabId = voucherId === 'new' ? 'voucher-new' : `voucher-${voucherId}`; const [dirty, setDirty] = useState(false)
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!), enabled: Boolean(session && ledgerId) })
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!), enabled: Boolean(session && ledgerId) })
  const voucher = useQuery({ queryKey: ['voucher', ledgerId, voucherId], queryFn: () => apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const watchedLines = Form.useWatch('lines', form)
  const lines = watchedLines ?? emptyLines
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
      const targetVoucher = persistedVoucher
      const targetVoucherId = targetVoucher?.id
      const updating = Boolean(targetVoucherId)
      const openPeriod = targetVoucher ? undefined : openPeriodForDate(periods.data || [], value.voucherDate)
      if (!targetVoucher && !openPeriod) {
        throw new Error('凭证日期不属于任何开放会计期间，请选择未结账期间内的日期。')
      }
      if (targetVoucher && !dateBelongsToPeriod(voucherPeriod, value.voucherDate)) {
        throw new Error(`凭证保存后不能修改会计期间，日期必须保留在 ${voucherPeriod?.periodCode || '原会计期间'}。`)
      }
      const requestValue = targetVoucher
        ? { ...value, periodId: targetVoucher.periodId, voucherNumber: targetVoucher.voucherNumber, voucherType: targetVoucher.voucherType }
        : value
      const body = buildVoucherRequestBody(requestValue, updating, targetVoucher?.version)
      return updating
        ? apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${targetVoucherId}`, session!, { method: 'PUT', body: jsonBody(body) })
        : apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers`, session!, { method: 'POST', headers: { 'Idempotency-Key': createIdempotencyKey() }, body: jsonBody(body) })
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
    onError: (error) => message.error(error instanceof ApiError && error.problem.code === 'VOUCHER_NOT_BALANCED'
      ? '借贷金额不平衡，凭证未保存。'
      : error instanceof ApiError && error.problem.code === 'VOUCHER_PERIOD_IMMUTABLE'
        ? '凭证保存后不能修改会计期间。'
        : error instanceof ApiError || error instanceof Error ? error.message : '凭证保存失败，请稍后重试。'),
  })
  const action = async (name: string, body?: unknown): Promise<boolean> => { if (!voucherId || voucherId === 'new' || pendingAction) return false; setPendingAction(name); try { await apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}:${name}`, session!, { method: 'POST', body: body ? jsonBody(body) : undefined }); await voucher.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); return true } catch (error) { modal.error(error instanceof ApiError && error.problem.code === 'RESOURCE_VERSION_CONFLICT' ? { title: '凭证已被其他人修改', content: '请刷新后确认是否放弃本地修改。' } : { title: '凭证操作失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); return false } finally { setPendingAction(null) } }
  const removeVoucher = async () => { if (!voucherId || voucherId === 'new' || pendingAction) return; setPendingAction('delete'); try { await apiFetch<void>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!, { method: 'DELETE' }); clearWorkspaceTabDirty(tabId); client.removeQueries({ queryKey: ['voucher', ledgerId, voucherId], exact: true }); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); message.success('凭证删除成功'); closeTab(tabId, { discardChanges: true }); navigate(`/ledgers/${ledgerId}/vouchers`) } catch (error) { message.error(error instanceof ApiError ? error.message : '删除凭证失败，请稍后重试。'); throw error } finally { setPendingAction(null) } }
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
        <Table className="voucher-entry-table" bordered size="middle" pagination={false} scroll={{ x: 900 }} rowKey="key" dataSource={fields}
          locale={{ emptyText: <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无分录" /> }}
          columns={[
            { title: '操作', width: 118, align: 'center', render: (_, field) => <Space size={0} className="voucher-line-actions"><Button type="link" icon={<PlusOutlined />} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' }, field.name)}>插入</Button><Button type="text" danger icon={<DeleteOutlined />} aria-label={`删除第 ${field.name + 1} 条分录`} onClick={() => remove(field.name)} /></Space> },
            { title: '摘要', width: 240, render: (_, field) => <><Form.Item name={[field.name, 'side']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'currency']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'exchangeRate']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'originalAmount']} hidden><Input /></Form.Item><Form.Item name={[field.name, 'summary']}><Input aria-label={`第 ${field.name + 1} 条分录摘要`} /></Form.Item></> },
            { title: '会计科目', width: 330, render: (_, field) => <Form.Item name={[field.name, 'accountId']}><Select showSearch optionFilterProp="label" aria-label={`第 ${field.name + 1} 条分录会计科目`} options={(accounts.data || []).filter((account) => account.status === 'ACTIVE' && account.isLeaf).map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item> },
            { title: '借方金额', width: 150, align: 'right', className: 'voucher-amount-column voucher-debit-column', render: (_, field) => <VoucherAmountCell form={form} fieldName={field.name} side="DEBIT" onChange={() => setDirty(true)} /> },
            { title: '贷方金额', width: 150, align: 'right', className: 'voucher-amount-column voucher-credit-column', render: (_, field) => <VoucherAmountCell form={form} fieldName={field.name} side="CREDIT" onChange={() => setDirty(true)} /> },
          ]}
          summary={() => <Table.Summary fixed><Table.Summary.Row className="voucher-total-row">
            <Table.Summary.Cell index={0} colSpan={3}><span>合计（本位币）</span></Table.Summary.Cell>
            <Table.Summary.Cell index={3} className="voucher-total-amount">{hasDebitAmount ? totals.debit.toFixed(2) : ''}</Table.Summary.Cell>
            <Table.Summary.Cell index={4} className="voucher-total-amount">{hasCreditAmount ? totals.credit.toFixed(2) : ''}</Table.Summary.Cell>
          </Table.Summary.Row></Table.Summary>}
        />
        <Button className="voucher-add-line" icon={<PlusOutlined />} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '', exchangeRate: '1' })}>新增分录</Button>
      </> }</Form.List>
      <Space>{editable && <Button type="primary" htmlType="submit" loading={save.isPending} disabled={Boolean(pendingAction)}>{persistedVoucher ? '保存修改' : '保存并记账'}</Button>}{voucherId && voucherId !== 'new' && editable && <>{status === 'DRAFT' && <Button loading={pendingAction === 'validate'} disabled={Boolean(pendingAction)} onClick={() => void action('validate')}>校验</Button>}{status === 'VALIDATED' && (voucher.data?.approvalRequired ? <Button loading={pendingAction === 'submit'} disabled={Boolean(pendingAction)} onClick={() => void action('submit')}>提交</Button> : <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>)}{status === 'SUBMITTED' && <><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('approve')}>审批</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('reject')}>退回</Button></>}{status === 'APPROVED' && <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>}<Button danger loading={pendingAction === 'delete'} disabled={Boolean(pendingAction)} onClick={() => modal.confirm({ title: '删除凭证？', content: '删除后会同步冲减余额投影。', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消', onOk: removeVoucher })}>删除凭证</Button></>}</Space>
    </Form></div></Card>
    <Modal open={Boolean(commentAction)} title="填写原因" okText="确认" cancelText="取消" confirmLoading={Boolean(pendingAction)} onCancel={() => setCommentAction(null)} onOk={async () => { const value = (document.querySelector('#voucher-action-comment') as HTMLInputElement)?.value; if (value && await action(commentAction || 'approve', { comment: value })) setCommentAction(null) }}><Input id="voucher-action-comment" placeholder="原因不能为空" /></Modal>
  </Space>
}
