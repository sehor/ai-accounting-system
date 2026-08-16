import { App as AntApp, Alert, Button, Card, Form, Input, Select, Space, Table, Tabs, Tag, Typography, Upload, message } from 'antd'
import type { FormInstance } from 'antd'
import { DownloadOutlined, UploadOutlined, PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { apiData, apiHeaders, openApiClient, ApiError, type ApiAuth } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { decimalOrZero } from '../features/vouchers/money'
import { LedgerBackupTab } from './LedgerBackupTab'
import { PeriodClosingPanel } from './PeriodClosingPanel'
import { OpeningDimensionFields, type OpeningFormLine } from './OpeningDimensionFields'
import { ReportFormulaSettingsTab } from './ReportFormulaSettingsTab'

type Account = components['schemas']['Account']
type DimensionType = components['schemas']['DimensionType']
type DimensionValue = components['schemas']['LedgerDimensionValue']
type Ledger = components['schemas']['LedgerResponse']
type Member = components['schemas']['Member']
type LedgerRole = Member['role']
type OpeningBalance = components['schemas']['OpeningBalanceResponse']
type Period = Omit<components['schemas']['Period'], 'hasVouchers'> & { hasVouchers?: boolean }
type User = components['schemas']['CurrentUser']

export const openingBalanceAmountPattern = /^-?\d+(?:\.\d+)?$/
const decimalRule = { pattern: openingBalanceAmountPattern, message: '请输入有效金额，可使用负数' }
const exchangeRateRule = { pattern: /^\d+(?:\.\d+)?$/, message: '请输入有效汇率' }
export const OPENING_BALANCE_CSV_HEADER = 'periodCode,accountCode,currency,dimensionKey,dimensionValues,debitOriginal,creditOriginal,exchangeRate'

export function downloadOpeningBalanceCsvTemplate() {
  const blob = new Blob([`${OPENING_BALANCE_CSV_HEADER}\n`], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const anchor = document.createElement('a')
  anchor.href = url
  anchor.download = 'opening-balances-template.csv'
  anchor.click()
  URL.revokeObjectURL(url)
}

export function SettingsPage() {
  const { ledgerId = '', '*': tab = 'periods' } = useParams(); const { session } = useAuth(); const client = useQueryClient(); const navigate = useNavigate(); const { modal } = AntApp.useApp(); const [messageApi, contextHolder] = message.useMessage()
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/periods', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const ledger = useQuery({ queryKey: ['ledger', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const openings = useQuery({ queryKey: ['openings', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/opening-balances', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const members = useQuery({ queryKey: ['members', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/members', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const ledgerRole = useQuery({ queryKey: ['ledger-role', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/role', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const dimensionTypes = useQuery({ queryKey: ['dimension-types', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const [selectedTypeId, setSelectedTypeId] = useState<string>()
  const [ledgerForm] = Form.useForm<{ name: string; description: string }>()
  useEffect(() => {
    if (tab === 'accounts') navigate(`/ledgers/${ledgerId}/accounts`, { replace: true })
  }, [ledgerId, navigate, tab])
  useEffect(() => { if (ledger.data) ledgerForm.setFieldsValue({ name: ledger.data.name, description: ledger.data.description || '' }) }, [ledger.data, ledgerForm])
  const ledgerUpdate = useMutation({
    mutationFn: (value: { name: string; description: string }) => apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: value })),
    onSuccess: (value) => { ledgerForm.setFieldsValue({ name: value.name, description: value.description || '' }); void client.invalidateQueries({ queryKey: ['ledger', ledgerId] }); messageApi.success('账套信息已保存') },
    onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '账套信息保存失败'),
  })
  const dimensionValues = useQuery({ queryKey: ['dimension-values', ledgerId, selectedTypeId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values', { params: { path: { ledgerId, typeId: selectedTypeId! } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId && selectedTypeId) })
  const periodAction = useMutation({ mutationFn: ({ period, operation }: { period: Period; operation: 'close' | 'reopen' }) => operation === 'close' ? apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/periods/{periodId}:close', { params: { path: { ledgerId, periodId: period.id } }, headers: apiHeaders(session!), body: { reason: 'Manual period action' } })) : apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/periods/{periodId}:reopen', { params: { path: { ledgerId, periodId: period.id } }, headers: apiHeaders(session!), body: { reason: 'Manual period action' } })), onSuccess: () => void client.invalidateQueries({ queryKey: ['periods', ledgerId] }) })
  const openingSave = useMutation({ mutationFn: (lines: OpeningFormLine[]) => apiData(openApiClient.PUT('/v1/ledgers/{ledgerId}/opening-balances', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: { lines: lines.map((line) => ({ ...line, dimensions: (line.dimensions || []).filter((dimension) => dimension.dimensionValueId).map((dimension) => ({ dimensionTypeId: dimension.dimensionTypeId, dimensionValueId: dimension.dimensionValueId! })), debitOriginal: String(line.debitOriginal), creditOriginal: String(line.creditOriginal), exchangeRate: String(line.exchangeRate) })) } })), onSuccess: () => { messageApi.success('期初余额已保存'); void client.invalidateQueries({ queryKey: ['openings', ledgerId] }) }, onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '期初余额保存失败') })
  const openingImport = useMutation({ mutationFn: (file: File) => { const body = new FormData(); body.append('file', file); return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/opening-balances:import-csv', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: { file: file as unknown as string }, bodySerializer: () => body })) }, onSuccess: () => { messageApi.success('CSV 已导入'); void client.invalidateQueries({ queryKey: ['openings', ledgerId] }) }, onError: (error) => messageApi.error(error instanceof ApiError ? error.message : 'CSV 导入失败') })
  const confirmOpening = useMutation({ mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/opening-balances:confirm', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), onSuccess: (value) => { messageApi.success(`已确认 ${value.confirmedCount} 行`); void client.invalidateQueries({ queryKey: ['openings', ledgerId] }) }, onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '期初余额确认失败') })
  const [emailForm] = Form.useForm<{ email: string }>(); const [candidate, setCandidate] = useState<User | null>(null)
  const findCandidate = async ({ email }: { email: string }) => { try { const result = await apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/member-candidates', { params: { path: { ledgerId }, query: { email } }, headers: apiHeaders(session!) })); setCandidate(result[0] || null); if (!result.length) messageApi.warning('没有找到已登录且有效的用户') } catch (error) { messageApi.error(error instanceof ApiError ? error.message : '成员查找失败') } }
  const addMember = async (role: LedgerRole) => { if (!candidate) return; try { await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/members', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: { userId: candidate.id, role } })); setCandidate(null); emailForm.resetFields(); messageApi.success('成员已添加'); void client.invalidateQueries({ queryKey: ['members', ledgerId] }) } catch (error) { messageApi.error(error instanceof ApiError ? error.message : '成员添加失败') } }
  const requestConfirmOpening = () => modal.confirm({ title: '确认期初余额？', content: '确认后将无法继续编辑或导入期初余额。', okText: '确认', okType: 'danger', cancelText: '取消', onOk: () => confirmOpening.mutateAsync() })
  const openClosePanel = (period: Period) => {
    const closeDialog = modal.info({ title: `期末结账 · ${period.periodCode}`, width: 980, icon: null, footer: null, closable: true, content: <PeriodClosingPanel ledgerId={ledgerId} session={session!} period={period} accounts={accounts.data || []} onDismiss={() => closeDialog.destroy()} onConfirmClose={() => { closeDialog.destroy(); periodAction.mutate({ period, operation: 'close' }) }} /> })
  }
  const changeTab = (key: string) => navigate(`/ledgers/${ledgerId}/settings/${key}`)
  return <>{contextHolder}<Space direction="vertical" size={16} style={{ width: '100%' }}><Typography.Title level={1}>账套设置</Typography.Title><Card title="账套基本信息"><Form form={ledgerForm} layout="vertical" onFinish={(value) => ledgerUpdate.mutate(value)}><Form.Item name="name" label="账套名称" rules={[{ required: true, message: '请输入账套名称' }]}><Input disabled={!['OWNER', 'EDITOR'].includes(ledgerRole.data?.role || '')} /></Form.Item><Form.Item name="description" label="公司主营业务" rules={[{ max: 2000, message: '主营业务描述不能超过 2000 个字符' }]}><Input.TextArea rows={4} maxLength={2000} showCount disabled={!['OWNER', 'EDITOR'].includes(ledgerRole.data?.role || '')} placeholder="例如：研发、生产和销售智能硬件及配套软件" /></Form.Item><Button type="primary" htmlType="submit" loading={ledgerUpdate.isPending} disabled={!['OWNER', 'EDITOR'].includes(ledgerRole.data?.role || '')}>保存</Button></Form></Card><Tabs activeKey={tab} onChange={changeTab} items={[
    { key: 'periods', label: '会计期间', children: <PeriodsTab periods={periods.data || []} onAction={(period, operation) => operation === 'close' ? void openClosePanel(period) : void periodAction.mutate({ period, operation: 'reopen' })} /> },
    { key: 'openings', label: '期初余额', children: <OpeningsTab ledgerId={ledgerId} auth={session!} rows={openings.data || []} accounts={accounts.data || []} periods={periods.data || []} onSave={(lines) => openingSave.mutate(lines)} saving={openingSave.isPending} onImport={(file) => openingImport.mutate(file)} importing={openingImport.isPending} onConfirm={requestConfirmOpening} confirming={confirmOpening.isPending} /> },
    { key: 'dimensions', label: '辅助核算', children: <DimensionsTab ledgerId={ledgerId} session={session!} types={dimensionTypes.data || []} values={dimensionValues.data || []} selectedTypeId={selectedTypeId} onSelect={setSelectedTypeId} onChanged={() => { void client.invalidateQueries({ queryKey: ['dimension-types', ledgerId] }); void client.invalidateQueries({ queryKey: ['dimension-values', ledgerId, selectedTypeId] }) }} /> },
    { key: 'members', label: '成员', children: <MembersTab rows={members.data || []} form={emailForm} candidate={candidate} onFind={findCandidate} onAdd={addMember} /> },
    { key: 'report-formulas', label: '报表公式', children: <ReportFormulaSettingsTab /> },
    { key: 'backup', label: '备份与恢复', children: <LedgerBackupTab ledgerId={ledgerId} session={session!} role={ledgerRole.data?.role as LedgerRole | undefined} onRestored={(ledger) => navigate(`/ledgers/${ledger.id}/settings/backup`)} /> },
  ]} /></Space></>
}

function PeriodsTab({ periods, onAction }: { periods: Period[]; onAction: (period: Period, operation: 'close' | 'reopen') => void }) { return <Card><Table rowKey="id" dataSource={periods} columns={[{ title: '期间', dataIndex: 'periodCode' }, { title: '起止日期', render: (_: unknown, p: Period) => `${p.startDate} ~ ${p.endDate}` }, { title: '状态', dataIndex: 'status', render: (value: string) => <Tag color={value === 'OPEN' ? 'green' : 'default'}>{value}</Tag> }, { title: '操作', render: (_: unknown, p: Period) => p.status === 'OPEN' ? <Button onClick={() => onAction(p, 'close')}>关账</Button> : <Button onClick={() => onAction(p, 'reopen')}>反结账</Button> }]} /></Card> }

export function OpeningsTab({ ledgerId, auth, rows, accounts, periods, onSave, saving, onImport, importing, onConfirm, confirming }: { ledgerId: string; auth: ApiAuth; rows: OpeningBalance[]; accounts: Account[]; periods: Period[]; onSave: (lines: OpeningFormLine[]) => void; saving: boolean; onImport: (file: File) => void; importing: boolean; onConfirm: () => void; confirming: boolean }) {
  const [form] = Form.useForm<{ lines: OpeningFormLine[] }>()
  useEffect(() => { form.setFieldsValue({ lines: rows.map((row) => ({ accountId: row.accountId, periodId: row.periodId, currency: row.currency, dimensionKey: row.dimensionKey || undefined, dimensions: (row.dimensions || []).map((dimension) => ({ dimensionTypeId: dimension.dimensionTypeId, dimensionValueId: dimension.dimensionValueId })), debitOriginal: row.debitOriginal, creditOriginal: row.creditOriginal, exchangeRate: row.exchangeRate })) }) }, [rows, form])
  const watchedLines = Form.useWatch('lines', form) || []
  const totals = watchedLines.reduce((result, line) => ({ debit: result.debit.plus(decimalOrZero(line.debitOriginal).times(decimalOrZero(line.exchangeRate))), credit: result.credit.plus(decimalOrZero(line.creditOriginal).times(decimalOrZero(line.exchangeRate))) }), { debit: decimalOrZero(0), credit: decimalOrZero(0) })
  const confirmed = rows.some((row) => row.confirmed)
  return <Card extra={<Space wrap>
    <Button icon={<DownloadOutlined />} onClick={downloadOpeningBalanceCsvTemplate}>下载 CSV 模板</Button>
    <Upload disabled={confirmed} accept=".csv,text/csv" showUploadList={false} beforeUpload={(file) => {
      if (!file.name.toLowerCase().endsWith('.csv')) {
        message.error('只支持 CSV 文件')
        return Upload.LIST_IGNORE
      }
      onImport(file)
      return false
    }}><Button icon={<UploadOutlined />} loading={importing} disabled={confirmed}>导入 CSV</Button></Upload>
    <Button onClick={() => form.submit()} loading={saving} disabled={confirmed}>保存</Button>
    <Button type="primary" onClick={onConfirm} loading={confirming} disabled={confirmed}>{confirmed ? '已确认' : '确认期初余额'}</Button>
  </Space>}>
    <Alert type="info" showIcon message="期初余额 CSV 格式" description={<Space direction="vertical" size={4}>
      <Typography.Text>请使用 UTF-8 CSV，首行必须严格使用以下字段，字段中不要包含逗号：</Typography.Text>
      <Typography.Text code copyable>{OPENING_BALANCE_CSV_HEADER}</Typography.Text>
      <Typography.Text>借方和贷方金额允许负数；系统按原列保存，不会把负数自动转到另一方向。同一行仍只能一侧为非零值。</Typography.Text>
    </Space>} />
    <Form form={form} disabled={confirmed} onFinish={(value) => onSave(value.lines)}>
      <Form.List name="lines">{(fields, { add, remove }) => <>
        <Table rowKey="key" dataSource={fields} pagination={false} scroll={{ x: 1000 }} columns={[
          { title: '科目', render: (_, field) => <Form.Item name={[field.name, 'accountId']} rules={[{ required: true }]}><Select showSearch optionFilterProp="label" onChange={() => { form.setFieldValue(['lines', field.name, 'dimensions'], []); form.setFieldValue(['lines', field.name, 'dimensionKey'], undefined) }} options={accounts.map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item> },
          { title: '期间', render: (_, field) => <Form.Item name={[field.name, 'periodId']} rules={[{ required: true }]}><Select options={periods.map((period) => ({ value: period.id, label: period.periodCode }))} /></Form.Item> },
          { title: '币种', render: (_, field) => <Form.Item name={[field.name, 'currency']} rules={[{ required: true, pattern: /^[A-Z]{3}$/ }]}><Input maxLength={3} /></Form.Item> },
          { title: '辅助核算', width: 260, render: (_, field) => <OpeningDimensionFields ledgerId={ledgerId} auth={auth} accounts={accounts} form={form} lineIndex={field.name} /> },
          { title: '借方', render: (_, field) => <Form.Item name={[field.name, 'debitOriginal']} rules={[{ required: true }, decimalRule]}><Input aria-label={`第 ${field.name + 1} 行借方金额`} inputMode="decimal" /></Form.Item> },
          { title: '贷方', render: (_, field) => <Form.Item name={[field.name, 'creditOriginal']} rules={[{ required: true }, decimalRule]}><Input aria-label={`第 ${field.name + 1} 行贷方金额`} inputMode="decimal" /></Form.Item> },
          { title: '汇率', render: (_, field) => <Form.Item name={[field.name, 'exchangeRate']} rules={[{ required: true }, exchangeRateRule]}><Input aria-label={`第 ${field.name + 1} 行汇率`} inputMode="decimal" /></Form.Item> },
          { title: '操作', render: (_, field) => <Button type="link" danger onClick={() => remove(field.name)}>删除</Button> },
        ]} />
        <Button icon={<PlusOutlined />} onClick={() => add({ currency: 'CNY', dimensions: [], debitOriginal: '0', creditOriginal: '0', exchangeRate: '1' })}>添加期初行</Button>
      </> }</Form.List>
    </Form>
    <Typography.Paragraph>借方合计：{totals.debit.toFixed(2)} / 贷方合计：{totals.credit.toFixed(2)}</Typography.Paragraph>
  </Card>
}

export function DimensionsTab({ ledgerId, session, types, values, selectedTypeId, onSelect, onChanged }: { ledgerId: string; session: NonNullable<ReturnType<typeof useAuth>['session']>; types: DimensionType[]; values: DimensionValue[]; selectedTypeId?: string; onSelect: (id: string) => void; onChanged: () => void }) {
  const [typeForm] = Form.useForm<{ code: string; name: string; required?: boolean }>(); const [valueForm] = Form.useForm<{ code: string; name: string }>()
  const [updating, setUpdating] = useState<string>()
  const createType = async (form: { code: string; name: string; required?: boolean }) => { await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/dimension-types', { params: { path: { ledgerId } }, headers: apiHeaders(session), body: form })); typeForm.resetFields(); onChanged() }
  const createValue = async (form: { code: string; name: string }) => { if (!selectedTypeId) return; await apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values', { params: { path: { ledgerId, typeId: selectedTypeId } }, headers: apiHeaders(session), body: form })); valueForm.resetFields(); onChanged() }
  const patchType = async (row: DimensionType, patch: { name?: string; status?: string }) => { setUpdating(row.id); try { await apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}/dimension-types/{typeId}', { params: { path: { ledgerId, typeId: row.id } }, headers: apiHeaders(session), body: { expectedVersion: row.version, ...patch } })); onChanged() } finally { setUpdating(undefined) } }
  const patchValue = async (row: DimensionValue, patch: { name?: string; status?: string }) => { if (!selectedTypeId) return; setUpdating(row.id); try { await apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values/{valueId}', { params: { path: { ledgerId, typeId: selectedTypeId, valueId: row.id } }, headers: apiHeaders(session), body: { expectedVersion: row.version, ...patch } })); onChanged() } finally { setUpdating(undefined) } }
  return <Space direction="vertical" style={{ width: '100%' }}><Card title="新增辅助核算类型"><Form form={typeForm} layout="inline" onFinish={(value) => void createType(value as { code: string; name: string; required?: boolean })}><Form.Item name="code" rules={[{ required: true }]}><Input placeholder="PROJECT" /></Form.Item><Form.Item name="name" rules={[{ required: true }]}><Input placeholder="项目" /></Form.Item><Form.Item name="required" valuePropName="checked"><input type="checkbox" aria-label="必填" /></Form.Item><Button htmlType="submit" type="primary">新增类型</Button></Form></Card><Card title="类型和值"><Space align="start" style={{ width: '100%' }}><Table rowKey="id" size="small" dataSource={types} pagination={false} style={{ flex: 1 }} columns={[{ title: '编码', dataIndex: 'code' }, { title: '名称', render: (_: unknown, row: DimensionType) => <Typography.Text editable={{ onChange: (name) => void patchType(row, { name }) }}>{row.name}</Typography.Text> }, { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{status}</Tag> }, { title: '必填', dataIndex: 'required', render: (value: boolean) => value ? '是' : '否' }, { title: '操作', render: (_: unknown, row: DimensionType) => <Space><Button type="link" onClick={() => onSelect(row.id)}>查看值</Button><Button type="link" loading={updating === row.id} onClick={() => void patchType(row, { status: row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}>{row.status === 'ACTIVE' ? '停用' : '启用'}</Button></Space> }]} /><Card size="small" title={types.find((type) => type.id === selectedTypeId)?.name || '请选择类型'} style={{ minWidth: 320 }}><Form form={valueForm} layout="inline" onFinish={(value) => void createValue(value as { code: string; name: string })}><Form.Item name="code" rules={[{ required: true }]}><Input placeholder="CODE" /></Form.Item><Form.Item name="name" rules={[{ required: true }]}><Input placeholder="名称" /></Form.Item><Button htmlType="submit" disabled={!selectedTypeId}>新增值</Button></Form><Table rowKey="id" size="small" dataSource={values} pagination={false} columns={[{ title: '编码', dataIndex: 'code' }, { title: '名称', render: (_: unknown, row: DimensionValue) => <Typography.Text editable={{ onChange: (name) => void patchValue(row, { name }) }}>{row.name}</Typography.Text> }, { title: '状态', dataIndex: 'status', render: (status: string) => <Tag>{status}</Tag> }, { title: '操作', render: (_: unknown, row: DimensionValue) => <Button type="link" loading={updating === row.id} onClick={() => void patchValue(row, { status: row.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE' })}>{row.status === 'ACTIVE' ? '停用' : '启用'}</Button> }]} /></Card></Space></Card></Space>
}

function MembersTab({ rows, form, candidate, onFind, onAdd }: { rows: Member[]; form: FormInstance<{ email: string }>; candidate: User | null; onFind: (value: { email: string }) => void; onAdd: (role: LedgerRole) => Promise<void> }) { const [role, setRole] = useState<LedgerRole>('VIEWER'); const [adding, setAdding] = useState(false); const add = async () => { setAdding(true); try { await onAdd(role) } finally { setAdding(false) } }; return <Space direction="vertical" style={{ width: '100%' }}><Card title="按邮箱添加已存在用户"><Form form={form} layout="inline" onFinish={(value) => void onFind(value as { email: string })}><Form.Item name="email" rules={[{ required: true, type: 'email' }]}><Input placeholder="user@example.com" /></Form.Item><Button htmlType="submit">精确查找</Button></Form>{candidate && <Space style={{ marginTop: 12 }}><Typography.Text>{candidate.displayName || candidate.email}</Typography.Text><Select value={role} options={['EDITOR', 'REVIEWER', 'VIEWER'].map((value) => ({ value, label: value }))} onChange={(value) => setRole(value as LedgerRole)} /><Button type="primary" loading={adding} onClick={() => void add()}>添加成员</Button></Space>}</Card><Card><Table rowKey="userId" dataSource={rows} columns={[{ title: '用户', render: (_: unknown, row: Member) => row.displayName || row.email || row.userId }, { title: '邮箱', dataIndex: 'email' }, { title: '角色', dataIndex: 'role' }, { title: '状态', dataIndex: 'status' }]} /></Card></Space> }
