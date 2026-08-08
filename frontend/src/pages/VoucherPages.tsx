import { Alert, App as AntApp, Button, Card, Checkbox, Col, DatePicker, Empty, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography, Upload } from 'antd'
import type { FormInstance } from 'antd'
import { DownloadOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { apiFetch, createIdempotencyKey, jsonBody, ApiError } from '../api/client'
import type { Account, CashFlowItem, DimensionValue, KingdeeImportResult, Period, Voucher, VoucherRevision } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { clearWorkspaceTabDirty, setWorkspaceTabDirty } from '../components/workspaceDirty'
import { voucherTotals } from '../features/vouchers/money'

export { VoucherListPage } from './VoucherListPage'

type VoucherForm = { periodId: string; voucherDate: Dayjs; voucherType: string; voucherNumber: string; summary?: string; lines: Array<{ accountId: string; side: 'DEBIT' | 'CREDIT'; currency: string; originalAmount: string; exchangeRate: string; summary?: string; cashFlowItemId?: string; quantity?: string; unitPrice?: string; dimensionValues?: Record<string, string> }> }
const emptyLines: VoucherForm['lines'] = []
const decimalRule = { pattern: /^\d+(?:\.\d+)?$/, message: '请输入有效数字' }

export function VoucherListPageLegacy() {
  const { ledgerId = '' } = useParams(); const { session } = useAuth(); const navigate = useNavigate(); const client = useQueryClient(); const { message } = AntApp.useApp(); const [search, setSearch] = useSearchParams()
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
  const statusLabel = (status: string) => ({ DRAFT: '草稿', VALIDATED: '已校验', SUBMITTED: '待审核', APPROVED: '已审核', POSTED: '已记账', REVERSED: '已冲销', DELETED: '已删除' }[status] || status)
  const statusColor = (status: string) => ({ DRAFT: 'default', VALIDATED: 'blue', SUBMITTED: 'orange', APPROVED: 'cyan', POSTED: 'green', REVERSED: 'purple', DELETED: 'red' }[status] || 'default')
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
        <Typography.Text type="secondary">仅合并同月、同业务类别且使用相同银行的凭证；不同银行的业务始终分开。</Typography.Text>
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

export function VoucherEditorPage() {
  const { ledgerId = '', voucherId } = useParams(); const { session } = useAuth(); const client = useQueryClient(); const navigate = useNavigate(); const { modal } = AntApp.useApp(); const [form] = Form.useForm<VoucherForm>(); const [commentAction, setCommentAction] = useState<'approve' | 'reject' | 'unpost' | null>(null); const [pendingAction, setPendingAction] = useState<string | null>(null)
  const tabId = voucherId === 'new' ? 'voucher-new' : `voucher-${voucherId}`; const [dirty, setDirty] = useState(false)
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!), enabled: Boolean(session && ledgerId) })
  const cashFlowItems = useQuery({ queryKey: ['cash-flow-items', ledgerId], queryFn: () => apiFetch<CashFlowItem[]>(`/ledgers/${ledgerId}/cash-flow-items`, session!), enabled: Boolean(session && ledgerId) })
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!), enabled: Boolean(session && ledgerId) })
  const voucher = useQuery({ queryKey: ['voucher', ledgerId, voucherId], queryFn: () => apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const revisions = useQuery({ queryKey: ['voucher-revisions', ledgerId, voucherId], queryFn: () => apiFetch<VoucherRevision[]>(`/ledgers/${ledgerId}/vouchers/${voucherId}/revisions`, session!), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const watchedLines = Form.useWatch('lines', form)
  const lines = watchedLines ?? emptyLines
  const totals = useMemo(() => voucherTotals(lines), [lines])
  const status = voucher.data?.status
  const editable = voucherId === 'new' || status === 'DRAFT'
  const save = useMutation({ mutationFn: async (value: VoucherForm) => { const body = { ...(voucherId && voucherId !== 'new' ? { expectedVersion: voucher.data?.version } : {}), periodId: value.periodId, voucherDate: value.voucherDate.format('YYYY-MM-DD'), voucherType: value.voucherType, voucherNumber: value.voucherNumber, summary: value.summary, lines: value.lines.map(({ dimensionValues, ...line }) => ({ ...line, originalAmount: String(line.originalAmount), exchangeRate: String(line.exchangeRate), dimensions: Object.entries(dimensionValues || {}).filter(([, dimensionValueId]) => Boolean(dimensionValueId)).map(([dimensionTypeId, dimensionValueId]) => ({ dimensionTypeId, dimensionValueId })) })) }; return voucherId && voucherId !== 'new' ? apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!, { method: 'PUT', body: jsonBody(body) }) : apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers`, session!, { method: 'POST', headers: { 'Idempotency-Key': createIdempotencyKey() }, body: jsonBody(body) }) }, onSuccess: (value) => { setDirty(false); clearWorkspaceTabDirty(tabId); client.setQueryData(['voucher', ledgerId, value.id], value); void client.invalidateQueries({ queryKey: ['voucher-revisions', ledgerId, value.id] }); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); if (voucherId === 'new') navigate(`/ledgers/${ledgerId}/vouchers/${value.id}`) }, onError: (error) => modal.error({ title: '凭证保存失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }) })
  const action = async (name: string, body?: unknown): Promise<boolean> => { if (!voucherId || voucherId === 'new' || pendingAction) return false; setPendingAction(name); try { await apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}:${name}`, session!, { method: 'POST', body: body ? jsonBody(body) : undefined }); await voucher.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); return true } catch (error) { modal.error(error instanceof ApiError && error.problem.code === 'RESOURCE_VERSION_CONFLICT' ? { title: '凭证已被其他人修改', content: '请刷新后确认是否放弃本地修改。' } : { title: '凭证操作失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); return false } finally { setPendingAction(null) } }
  const restoreRevision = async (revision: number) => { if (!voucherId || voucherId === 'new' || pendingAction) return; setPendingAction('restore'); try { await apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}/revisions/${revision}:restore`, session!, { method: 'POST' }); await voucher.refetch(); void revisions.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }) } catch (error) { modal.error({ title: '恢复历史版本失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); throw error } finally { setPendingAction(null) } }
  const initial = voucher.data ? { ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } : { voucherDate: dayjs(), voucherType: '记', lines: [{ side: 'DEBIT', currency: 'CNY', originalAmount: '0', exchangeRate: '1' }] }
  useEffect(() => { if (voucher.data) form.setFieldsValue({ ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } as VoucherForm) }, [voucher.data, form])
  useEffect(() => { setWorkspaceTabDirty(tabId, dirty); if (!dirty) return; const beforeUnload = (event: BeforeUnloadEvent) => { event.preventDefault() }; window.addEventListener('beforeunload', beforeUnload); return () => window.removeEventListener('beforeunload', beforeUnload) }, [dirty, tabId])
  useEffect(() => () => clearWorkspaceTabDirty(tabId), [tabId])
  if (voucher.isError) return <Alert type="error" message="凭证读取失败" />
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>{voucherId === 'new' ? '新建凭证' : '凭证详情'}</Typography.Title><Typography.Text type="secondary">{voucher.data ? `版本 ${voucher.data.version} · ${voucher.data.status}` : '保存成功后自动审批并记账'}</Typography.Text></div><Button onClick={() => navigate(`/ledgers/${ledgerId}/vouchers`)}>返回列表</Button></div>
    <Card><Form form={form} layout="vertical" initialValues={initial} disabled={!editable} onValuesChange={() => setDirty(true)} onFinish={(value) => save.mutate(value as VoucherForm)}>
      <Row gutter={16}><Col xs={24} md={8}><Form.Item name="periodId" label="会计期间" rules={[{ required: true }]}><Select options={(periods.data || []).map((period) => ({ value: period.id, label: `${period.periodCode} (${period.status})` }))} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="voucherDate" label="凭证日期" rules={[{ required: true }]}><DatePicker style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="voucherType" label="凭证字" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
      <Row gutter={16}><Col xs={24} md={8}><Form.Item name="voucherNumber" label="凭证号" rules={[{ required: true }]}><Input /></Form.Item></Col><Col xs={24} md={16}><Form.Item name="summary" label="摘要"><Input /></Form.Item></Col></Row>
      <Form.List name="lines">{(fields, { add, remove }) => <><Table pagination={false} scroll={{ x: 1300 }} rowKey="key" dataSource={fields} columns={[{ title: '科目', width: 220, render: (_, field) => <Form.Item name={[field.name, 'accountId']} rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={(accounts.data || []).filter((account) => account.status === 'ACTIVE' && account.isLeaf).map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item> }, { title: '方向', width: 90, render: (_, field) => <Form.Item name={[field.name, 'side']} rules={[{ required: true }]}><Select options={[{ value: 'DEBIT', label: '借' }, { value: 'CREDIT', label: '贷' }]} /></Form.Item> }, { title: '币种', width: 90, render: (_, field) => <Form.Item name={[field.name, 'currency']} rules={[{ required: true }]}><Input maxLength={3} /></Form.Item> }, { title: '原币金额', width: 140, render: (_, field) => <Form.Item name={[field.name, 'originalAmount']} rules={[{ required: true }, decimalRule]}><Input inputMode="decimal" /></Form.Item> }, { title: '汇率', width: 120, render: (_, field) => <Form.Item name={[field.name, 'exchangeRate']} rules={[{ required: true }, decimalRule]}><Input inputMode="decimal" /></Form.Item> }, { title: '辅助与控制项', width: 420, render: (_, field) => <VoucherLineControls form={form} fieldName={field.name} ledgerId={ledgerId} session={session!} accounts={accounts.data || []} cashFlowItems={cashFlowItems.data || []} /> }, { title: '操作', width: 80, fixed: 'right', render: (_, field) => <Button type="link" danger onClick={() => remove(field.name)}>删除</Button> }]} /><Button style={{ marginTop: 12 }} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '0', exchangeRate: '1' })}>添加分录</Button></> }</Form.List>
      <Typography.Paragraph>借方合计：{totals.debit.toFixed(2)} / 贷方合计：{totals.credit.toFixed(2)}</Typography.Paragraph>
      <Space>{editable && <Button type="primary" htmlType="submit" loading={save.isPending} disabled={Boolean(pendingAction) || (voucherId !== 'new' && voucher.isLoading)}>保存并记账</Button>}{voucherId && voucherId !== 'new' && <>{status === 'DRAFT' && <Button loading={pendingAction === 'validate'} disabled={Boolean(pendingAction)} onClick={() => void action('validate')}>校验</Button>}{status === 'VALIDATED' && (voucher.data?.approvalRequired ? <Button loading={pendingAction === 'submit'} disabled={Boolean(pendingAction)} onClick={() => void action('submit')}>提交</Button> : <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>)}{status === 'SUBMITTED' && <><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('approve')}>审批</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('reject')}>退回</Button></>}{status === 'APPROVED' && <Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button>}{status === 'POSTED' && <><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('unpost')}>反记账</Button><Button danger loading={pendingAction === 'reverse'} disabled={Boolean(pendingAction)} onClick={() => void action('reverse')}>冲销</Button></>}</>}</Space>
    </Form></Card>
    {voucherId && voucherId !== 'new' && <Card title="历史版本" extra={<Typography.Text type="secondary">恢复后会生成新的版本</Typography.Text>}><Table rowKey="revision" loading={revisions.isLoading} dataSource={revisions.data || []} pagination={false} scroll={{ x: 800 }} columns={[{ title: '修订号', dataIndex: 'revision' }, { title: '操作', dataIndex: 'action' }, { title: '原因', dataIndex: 'reason' }, { title: '时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') }, { title: '操作', render: (_: unknown, row: VoucherRevision) => <Button onClick={() => modal.confirm({ title: `恢复修订 ${row.revision}？`, content: '当前内容会保留在新的历史版本中。', okText: '恢复', cancelText: '取消', onOk: () => restoreRevision(row.revision) })}>恢复</Button> }]} /></Card>}
    <Modal open={Boolean(commentAction)} title="填写原因" okText="确认" cancelText="取消" confirmLoading={Boolean(pendingAction)} onCancel={() => setCommentAction(null)} onOk={async () => { const value = (document.querySelector('#voucher-action-comment') as HTMLInputElement)?.value; if (value && await action(commentAction === 'unpost' ? 'unpost' : commentAction || 'approve', commentAction === 'unpost' ? { reason: value } : { comment: value })) setCommentAction(null) }}><Input id="voucher-action-comment" placeholder="原因不能为空" /></Modal>
  </Space>
}

function VoucherLineControls({ form, fieldName, ledgerId, session, accounts, cashFlowItems }: {
  form: FormInstance<VoucherForm>
  fieldName: number
  ledgerId: string
  session: NonNullable<ReturnType<typeof useAuth>['session']>
  accounts: Account[]
  cashFlowItems: CashFlowItem[]
}) {
  const accountId = Form.useWatch(['lines', fieldName, 'accountId'], form)
  const account = accounts.find((item) => item.id === accountId)
  if (!account) return <Typography.Text type="secondary">选择科目后显示</Typography.Text>
  return <Space direction="vertical" size={4} style={{ width: '100%' }}>
    {account.cashFlowRequired && <Form.Item name={[fieldName, 'cashFlowItemId']} label="现金流项目" style={{ marginBottom: 4 }}>
      <Select allowClear placeholder="校验前必填" options={cashFlowItems.map((item) => ({
        value: item.id,
        label: `${item.code} ${item.name}`,
      }))} />
    </Form.Item>}
    {account.quantityEnabled && <Space align="start">
      <Form.Item name={[fieldName, 'quantity']} label={`数量（${account.unitName}）`} style={{ marginBottom: 4 }}>
        <Input inputMode="decimal" />
      </Form.Item>
      <Form.Item name={[fieldName, 'unitPrice']} label="单价" style={{ marginBottom: 4 }}>
        <Input inputMode="decimal" />
      </Form.Item>
    </Space>}
    {account.dimensionRequirements.map((requirement) =>
      <DimensionValueField key={requirement.dimensionTypeId} fieldName={fieldName}
        ledgerId={ledgerId} session={session} requirement={requirement} />)}
    {!account.cashFlowRequired && !account.quantityEnabled && account.dimensionRequirements.length === 0 &&
      <Typography.Text type="secondary">无控制项</Typography.Text>}
  </Space>
}

function DimensionValueField({ fieldName, ledgerId, session, requirement }: {
  fieldName: number
  ledgerId: string
  session: NonNullable<ReturnType<typeof useAuth>['session']>
  requirement: Account['dimensionRequirements'][number]
}) {
  const values = useQuery({
    queryKey: ['dimension-values', ledgerId, requirement.dimensionTypeId],
    queryFn: () => apiFetch<DimensionValue[]>(
      `/ledgers/${ledgerId}/dimension-types/${requirement.dimensionTypeId}/values`, session,
    ),
  })
  return <Form.Item name={[fieldName, 'dimensionValues', requirement.dimensionTypeId]}
    label={`${requirement.name}${requirement.required ? '（校验前必填）' : ''}`} style={{ marginBottom: 4 }}>
    <Select allowClear loading={values.isLoading} options={(values.data || []).filter((item) => item.status === 'ACTIVE')
      .map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} />
  </Form.Item>
}
