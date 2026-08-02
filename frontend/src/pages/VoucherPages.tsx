import { Alert, App as AntApp, Button, Card, Col, DatePicker, Empty, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { FormInstance } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { apiFetch, createIdempotencyKey, jsonBody, ApiError } from '../api/client'
import type { Account, CashFlowItem, DimensionValue, Period, Voucher, VoucherRevision } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { voucherTotals } from '../features/vouchers/money'

type VoucherForm = { periodId: string; voucherDate: Dayjs; voucherType: string; voucherNumber: string; summary?: string; lines: Array<{ accountId: string; side: 'DEBIT' | 'CREDIT'; currency: string; originalAmount: string; exchangeRate: string; summary?: string; cashFlowItemId?: string; quantity?: string; unitPrice?: string; dimensionValues?: Record<string, string> }> }
const emptyLines: VoucherForm['lines'] = []
const decimalRule = { pattern: /^\d+(?:\.\d+)?$/, message: '请输入有效数字' }

export function VoucherListPage() {
  const { ledgerId = '' } = useParams(); const { session } = useAuth(); const navigate = useNavigate(); const [search, setSearch] = useSearchParams()
  const limit = Number(search.get('limit') || 20); const offset = Number(search.get('offset') || 0)
  const query = useQuery({ queryKey: ['vouchers', ledgerId, limit, offset], queryFn: () => apiFetch<Voucher[]>(`/ledgers/${ledgerId}/vouchers?limit=${limit}&offset=${offset}`, session!), enabled: Boolean(session && ledgerId) })
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>凭证工作台</Typography.Title><Typography.Text type="secondary">录入、校验并推进凭证状态。</Typography.Text></div><Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/vouchers/new`)}>新建凭证</Button></div>
    {query.isError && <Alert type="error" showIcon message="凭证列表读取失败" action={<Button icon={<ReloadOutlined />} onClick={() => void query.refetch()}>重试</Button>} />}
    <Card><Table rowKey="id" loading={query.isLoading} dataSource={query.data || []} locale={{ emptyText: <Empty description="暂无凭证" /> }} pagination={{ current: Math.floor(offset / limit) + 1, pageSize: limit, total: (offset + (query.data?.length || 0)) + (query.data?.length === limit ? 1 : 0), showSizeChanger: false, onChange: (page) => setSearch({ limit: String(limit), offset: String((page - 1) * limit) }) }} columns={[
      { title: '凭证号', dataIndex: 'voucherNumber', render: (value: string, row: Voucher) => <Link to={`/ledgers/${ledgerId}/vouchers/${row.id}`}>{row.voucherType}-{value}</Link> },
      { title: '日期', dataIndex: 'voucherDate' }, { title: '摘要', dataIndex: 'summary' }, { title: '状态', dataIndex: 'status', render: (value: string) => <Tag>{value}</Tag> }, { title: '版本', dataIndex: 'version' },
    ]} /></Card>
  </Space>
}

export function VoucherEditorPage() {
  const { ledgerId = '', voucherId } = useParams(); const { session } = useAuth(); const client = useQueryClient(); const navigate = useNavigate(); const { modal } = AntApp.useApp(); const [form] = Form.useForm<VoucherForm>(); const [commentAction, setCommentAction] = useState<'approve' | 'reject' | 'unpost' | null>(null); const [pendingAction, setPendingAction] = useState<string | null>(null)
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!), enabled: Boolean(session && ledgerId) })
  const cashFlowItems = useQuery({ queryKey: ['cash-flow-items', ledgerId], queryFn: () => apiFetch<CashFlowItem[]>(`/ledgers/${ledgerId}/cash-flow-items`, session!), enabled: Boolean(session && ledgerId) })
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!), enabled: Boolean(session && ledgerId) })
  const voucher = useQuery({ queryKey: ['voucher', ledgerId, voucherId], queryFn: () => apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const revisions = useQuery({ queryKey: ['voucher-revisions', ledgerId, voucherId], queryFn: () => apiFetch<VoucherRevision[]>(`/ledgers/${ledgerId}/vouchers/${voucherId}/revisions`, session!), enabled: Boolean(session && ledgerId && voucherId && voucherId !== 'new') })
  const watchedLines = Form.useWatch('lines', form)
  const lines = watchedLines ?? emptyLines
  const totals = useMemo(() => voucherTotals(lines), [lines])
  const save = useMutation({ mutationFn: async (value: VoucherForm) => { const body = { ...(voucherId && voucherId !== 'new' ? { expectedVersion: voucher.data?.version } : {}), periodId: value.periodId, voucherDate: value.voucherDate.format('YYYY-MM-DD'), voucherType: value.voucherType, voucherNumber: value.voucherNumber, summary: value.summary, lines: value.lines.map(({ dimensionValues, ...line }) => ({ ...line, originalAmount: String(line.originalAmount), exchangeRate: String(line.exchangeRate), dimensions: Object.entries(dimensionValues || {}).filter(([, dimensionValueId]) => Boolean(dimensionValueId)).map(([dimensionTypeId, dimensionValueId]) => ({ dimensionTypeId, dimensionValueId })) })) }; return voucherId && voucherId !== 'new' ? apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}`, session!, { method: 'PUT', body: jsonBody(body) }) : apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers`, session!, { method: 'POST', headers: { 'Idempotency-Key': createIdempotencyKey() }, body: jsonBody(body) }) }, onSuccess: (value) => { client.setQueryData(['voucher', ledgerId, value.id], value); void client.invalidateQueries({ queryKey: ['voucher-revisions', ledgerId, value.id] }); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); if (voucherId === 'new') navigate(`/ledgers/${ledgerId}/vouchers/${value.id}`) }, onError: (error) => modal.error({ title: '凭证保存失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }) })
  const action = async (name: string, body?: unknown): Promise<boolean> => { if (!voucherId || voucherId === 'new' || pendingAction) return false; setPendingAction(name); try { await apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}:${name}`, session!, { method: 'POST', body: body ? jsonBody(body) : undefined }); await voucher.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }); return true } catch (error) { modal.error(error instanceof ApiError && error.status === 409 ? { title: '凭证已被其他人修改', content: '请刷新后确认是否放弃本地修改。' } : { title: '凭证操作失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); return false } finally { setPendingAction(null) } }
  const restoreRevision = async (revision: number) => { if (!voucherId || voucherId === 'new' || pendingAction) return; setPendingAction('restore'); try { await apiFetch<Voucher>(`/ledgers/${ledgerId}/vouchers/${voucherId}/revisions/${revision}:restore`, session!, { method: 'POST' }); await voucher.refetch(); void revisions.refetch(); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }) } catch (error) { modal.error({ title: '恢复历史版本失败', content: error instanceof ApiError ? error.message : '请稍后重试。' }); throw error } finally { setPendingAction(null) } }
  const initial = voucher.data ? { ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } : { voucherDate: dayjs(), voucherType: '记', lines: [{ side: 'DEBIT', currency: 'CNY', originalAmount: '0', exchangeRate: '1' }] }
  useEffect(() => { if (voucher.data) form.setFieldsValue({ ...voucher.data, voucherDate: dayjs(voucher.data.voucherDate), lines: voucher.data.lines.map((line) => ({ ...line, originalAmount: line.originalAmount, exchangeRate: line.exchangeRate, dimensionValues: Object.fromEntries(line.dimensions.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId])) })) } as VoucherForm) }, [voucher.data, form])
  if (voucher.isError) return <Alert type="error" message="凭证读取失败" />
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>{voucherId === 'new' ? '新建凭证' : '凭证详情'}</Typography.Title><Typography.Text type="secondary">{voucher.data ? `版本 ${voucher.data.version} · ${voucher.data.status}` : '保存后可继续校验和记账'}</Typography.Text></div><Button onClick={() => navigate(`/ledgers/${ledgerId}/vouchers`)}>返回列表</Button></div>
    <Card><Form form={form} layout="vertical" initialValues={initial} onFinish={(value) => save.mutate(value as VoucherForm)}>
      <Row gutter={16}><Col xs={24} md={8}><Form.Item name="periodId" label="会计期间" rules={[{ required: true }]}><Select options={(periods.data || []).map((period) => ({ value: period.id, label: `${period.periodCode} (${period.status})` }))} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="voucherDate" label="凭证日期" rules={[{ required: true }]}><DatePicker style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="voucherType" label="凭证字" rules={[{ required: true }]}><Input /></Form.Item></Col></Row>
      <Row gutter={16}><Col xs={24} md={8}><Form.Item name="voucherNumber" label="凭证号" rules={[{ required: true }]}><Input /></Form.Item></Col><Col xs={24} md={16}><Form.Item name="summary" label="摘要"><Input /></Form.Item></Col></Row>
      <Form.List name="lines">{(fields, { add, remove }) => <><Table pagination={false} scroll={{ x: 1300 }} rowKey="key" dataSource={fields} columns={[{ title: '科目', width: 220, render: (_, field) => <Form.Item name={[field.name, 'accountId']} rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={(accounts.data || []).filter((account) => account.status === 'ACTIVE' && account.isLeaf).map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item> }, { title: '方向', width: 90, render: (_, field) => <Form.Item name={[field.name, 'side']} rules={[{ required: true }]}><Select options={[{ value: 'DEBIT', label: '借' }, { value: 'CREDIT', label: '贷' }]} /></Form.Item> }, { title: '币种', width: 90, render: (_, field) => <Form.Item name={[field.name, 'currency']} rules={[{ required: true }]}><Input maxLength={3} /></Form.Item> }, { title: '原币金额', width: 140, render: (_, field) => <Form.Item name={[field.name, 'originalAmount']} rules={[{ required: true }, decimalRule]}><Input inputMode="decimal" /></Form.Item> }, { title: '汇率', width: 120, render: (_, field) => <Form.Item name={[field.name, 'exchangeRate']} rules={[{ required: true }, decimalRule]}><Input inputMode="decimal" /></Form.Item> }, { title: '辅助与控制项', width: 420, render: (_, field) => <VoucherLineControls form={form} fieldName={field.name} ledgerId={ledgerId} session={session!} accounts={accounts.data || []} cashFlowItems={cashFlowItems.data || []} /> }, { title: '操作', width: 80, fixed: 'right', render: (_, field) => <Button type="link" danger onClick={() => remove(field.name)}>删除</Button> }]} /><Button style={{ marginTop: 12 }} onClick={() => add({ side: 'DEBIT', currency: 'CNY', originalAmount: '0', exchangeRate: '1' })}>添加分录</Button></> }</Form.List>
      <Typography.Paragraph>借方合计：{totals.debit.toFixed(2)} / 贷方合计：{totals.credit.toFixed(2)}</Typography.Paragraph>
      <Space><Button type="primary" htmlType="submit" loading={save.isPending} disabled={Boolean(pendingAction) || (voucherId !== 'new' && voucher.isLoading)}>保存草稿</Button>{voucherId && voucherId !== 'new' && <><Button loading={pendingAction === 'validate'} disabled={Boolean(pendingAction)} onClick={() => void action('validate')}>校验</Button><Button loading={pendingAction === 'submit'} disabled={Boolean(pendingAction)} onClick={() => void action('submit')}>提交</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('approve')}>审批</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('reject')}>退回</Button><Button type="primary" loading={pendingAction === 'post'} disabled={Boolean(pendingAction)} onClick={() => void action('post')}>记账</Button><Button disabled={Boolean(pendingAction)} onClick={() => setCommentAction('unpost')}>反记账</Button><Button danger loading={pendingAction === 'reverse'} disabled={Boolean(pendingAction)} onClick={() => void action('reverse')}>冲销</Button></>}</Space>
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
