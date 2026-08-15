import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Col, DatePicker, Descriptions, Empty, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tag, Typography, Upload, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { DeleteOutlined, PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import dayjs, { type Dayjs } from 'dayjs'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, apiData, apiHeaders, openApiClient } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

type Account = components['schemas']['Account']
type DimensionType = components['schemas']['DimensionType']
type DimensionValue = components['schemas']['LedgerDimensionValue']
type FixedAsset = components['schemas']['FixedAsset']
type FixedAssetCreate = components['schemas']['AssetCreate']
type FixedAssetPatch = components['schemas']['AssetPatch']
type FixedAssetCategory = components['schemas']['FixedAssetCategory']
type FixedAssetCategoryCreate = components['schemas']['CategoryCreate']
type FixedAssetPage = components['schemas']['FixedAssetPage']
type Period = Omit<components['schemas']['Period'], 'hasVouchers'> & { hasVouchers?: boolean }

export const formatFixedAssetMoney = (value: string | number | null | undefined) => value == null ? '-' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
export const fixedAssetTabs = ['cards', 'categories', 'disposals'] as const
export const normalizeFixedAssetTab = (value: string | null) => fixedAssetTabs.includes(value as (typeof fixedAssetTabs)[number]) ? value as (typeof fixedAssetTabs)[number] : 'cards'
export const departmentNameById = (values: DimensionValue[]) => new Map(values.map((value) => [value.id, value.name]))

const money = formatFixedAssetMoney
const accountFields = [
  ['assetAccountId', '固定资产科目'],
  ['accumulatedDepreciationAccountId', '累计折旧科目'],
  ['depreciationExpenseAccountId', '折旧费用科目'],
  ['impairmentAccountId', '减值准备科目'],
  ['clearingAccountId', '固定资产清理科目'],
  ['disposalGainAccountId', '处置收益科目'],
  ['disposalLossAccountId', '处置损失科目'],
] as const

type DisposeFormValues = {
  disposalDate?: Dayjs
  reason?: string
  proceeds?: number
  outputTax?: number
  clearingCost?: number
  clearingInputTax?: number
  receiptAccountId?: string
  paymentAccountId?: string
  outputTaxAccountId?: string
  inputTaxAccountId?: string
}

function useLedgerData(ledgerId: string) {
  const { session } = useAuth()
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/periods', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const categories = useQuery({ queryKey: ['fixed-asset-categories', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-asset-categories', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const dimensionTypes = useQuery({ queryKey: ['dimension-types', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  const departmentType = dimensionTypes.data?.find((type) => type.code === 'DEPARTMENT')
  const departmentValues = useQuery({
    queryKey: ['dimension-values', ledgerId, departmentType?.id],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values', { params: { path: { ledgerId, typeId: departmentType!.id } }, headers: apiHeaders(session!) })),
    enabled: Boolean(session && ledgerId && departmentType),
  })
  return { session, periods, categories, dimensionTypes, departmentValues }
}

function apiErrorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

async function downloadFixedAssetTemplate(ledgerId: string, session: NonNullable<ReturnType<typeof useAuth>['session']>) {
  return apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-assets/import-template', {
    params: { path: { ledgerId } }, headers: apiHeaders(session), parseAs: 'blob',
  })) as unknown as Blob
}

async function importFixedAssets(ledgerId: string, session: NonNullable<ReturnType<typeof useAuth>['session']>, file: File) {
  const body = new FormData()
  body.append('file', file)
  return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-assets/import', {
    params: { path: { ledgerId } }, headers: apiHeaders(session),
    body: { file: file as unknown as string }, bodySerializer: () => body,
  }))
}

export function fixedAssetPayload(values: Record<string, unknown>, editing: false): FixedAssetCreate
export function fixedAssetPayload(values: Record<string, unknown>, editing: true): Omit<FixedAssetPatch, 'expectedVersion' | 'changePeriodId' | 'reason'>
export function fixedAssetPayload(values: Record<string, unknown>, editing: boolean) {
  const { currentDepreciation, currentAccumulatedDepreciation, endingAccumulatedDepreciation, openingNetValue, endingNetValue, residualAmount, ...input } = values
  const normalized: Record<string, unknown> = {
    ...input,
    serviceDate: dayjs.isDayjs(input.serviceDate) ? input.serviceDate.format('YYYY-MM-DD') : input.serviceDate,
    quantity: String(input.quantity ?? 1),
    originalCost: String(input.originalCost ?? ''),
    inputTax: String(input.inputTax ?? 0),
    residualRate: String(input.residualRate ?? ''),
    openingAccumulatedDepreciation: String(input.openingAccumulatedDepreciation ?? 0),
    openingDepreciatedMonths: input.openingDepreciatedMonths ?? 0,
    impairmentAmount: String(input.impairmentAmount ?? 0),
  }
  if (!editing) return normalized as FixedAssetCreate
  const { categoryId, code, openingAccumulatedDepreciation, openingDepreciatedMonths, ...patch } = normalized
  return patch as Omit<FixedAssetPatch, 'expectedVersion' | 'changePeriodId' | 'reason'>
}

export function FixedAssetListPage() {
  const { ledgerId = '' } = useParams()
  const { session, periods, categories, dimensionTypes, departmentValues } = useLedgerData(ledgerId)
  const navigate = useNavigate()
  const [search, setSearch] = useWorkspaceSearchParams()
  const tab = normalizeFixedAssetTab(search.get('tab'))
  const period = periods.data?.find((item) => item.status === 'OPEN') || periods.data?.[periods.data.length - 1]
  const categoryId = search.get('categoryId') || undefined
  const keyword = search.get('keyword') || undefined
  const assets = useQuery({ queryKey: ['fixed-assets', ledgerId, period?.id, categoryId, keyword], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-assets', { params: { path: { ledgerId }, query: { periodId: period!.id, page: 1, pageSize: 100, categoryId, search: keyword } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId && period) })
  const referenceFailed = periods.isError || categories.isError || dimensionTypes.isError || departmentValues.isError || assets.isError
  const departments = useMemo(() => departmentNameById(departmentValues.data || []), [departmentValues.data])
  const setTab = (value: string) => { const next = new URLSearchParams(search); next.set('tab', normalizeFixedAssetTab(value)); setSearch(next) }
  const columns: ColumnsType<FixedAsset> = [
    { title: '编号', dataIndex: 'code', fixed: 'left', render: (value, row) => <Link to={`/ledgers/${ledgerId}/fixed-assets/${row.id}`}>{value}</Link> },
    { title: '名称', dataIndex: 'name' }, { title: '类别', dataIndex: 'categoryName' },
    { title: '部门', dataIndex: 'departmentValueId', render: (value: string | null) => value ? departments.get(value) || '-' : '-' },
    { title: '原值', dataIndex: 'originalCost', render: money }, { title: '本期折旧', dataIndex: 'currentAccumulatedDepreciation', render: money },
    { title: '期末累计折旧', dataIndex: 'endingAccumulatedDepreciation', render: money }, { title: '月折旧', dataIndex: 'currentDepreciation', render: money },
    { title: '期末净值', dataIndex: 'endingNetValue', render: money }, { title: '状态', dataIndex: 'status', render: (value) => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value === 'ACTIVE' ? '正常使用' : '已清理'}</Tag> },
  ]
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>固定资产</Typography.Title><Typography.Text type="secondary">资产卡片、类别与处置记录</Typography.Text></div><Space wrap><Button icon={<ReloadOutlined />} onClick={() => assets.refetch()}>刷新</Button><Button onClick={async () => { try { const blob = await downloadFixedAssetTemplate(ledgerId, session!); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'fixed-assets-template.xlsx'; link.click(); URL.revokeObjectURL(url) } catch (error) { message.error(apiErrorMessage(error, '下载模板失败')) } }}>下载模板</Button><Upload disabled={referenceFailed} accept=".xlsx" showUploadList={false} beforeUpload={async (file) => { try { const result = await importFixedAssets(ledgerId, session!, file); if (result.committed) { message.success(`已导入 ${result.rowCount} 张卡片`); await assets.refetch() } else message.error(result.errors.join('；')) } catch (error) { message.error(apiErrorMessage(error, '导入失败')) } return false }}><Button disabled={referenceFailed} icon={<UploadOutlined />}>导入 Excel</Button></Upload><Button disabled={referenceFailed} type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/fixed-assets/new`)}>新增卡片</Button></Space></div>
    {referenceFailed && <Alert type="error" showIcon message="固定资产列表加载失败" description="无法安全确认期间、类别或部门信息，请刷新后重试。" />}
    <Card className="financial-grid-card">
      <div className="fixed-asset-tabs" role="tablist" aria-label="固定资产页面"><Button role="tab" aria-selected={tab === 'cards'} type={tab === 'cards' ? 'primary' : 'text'} onClick={() => setTab('cards')}>资产卡片</Button><Button role="tab" aria-selected={tab === 'categories'} type={tab === 'categories' ? 'primary' : 'text'} onClick={() => setTab('categories')}>资产类别</Button><Button role="tab" aria-selected={tab === 'disposals'} type={tab === 'disposals' ? 'primary' : 'text'} onClick={() => setTab('disposals')}>处置记录</Button></div>
      {tab === 'cards' && <div className="fixed-asset-list"><Space wrap><Select allowClear placeholder="类别" style={{ width: 180 }} value={categoryId} options={(categories.data || []).map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} onChange={(value) => { const next = new URLSearchParams(search); if (value) next.set('categoryId', value); else next.delete('categoryId'); setSearch(next) }} /><Input.Search allowClear placeholder="编号或名称" style={{ width: 240 }} defaultValue={keyword} onSearch={(value) => { const next = new URLSearchParams(search); if (value) next.set('keyword', value); else next.delete('keyword'); setSearch(next) }} /><Tag color="blue">当前期间：{period?.periodCode || '-'}</Tag></Space><Table className="financial-table" rowKey="id" loading={assets.isLoading || departmentValues.isLoading} dataSource={assets.data?.data || []} columns={columns} scroll={{ x: 1260 }} pagination={{ total: assets.data?.totalItems || 0, pageSize: assets.data?.pageSize || 100, hideOnSinglePage: true }} locale={{ emptyText: <Empty description="暂无资产卡片" /> }} /></div>}
      {tab === 'categories' && <CategoryTab ledgerId={ledgerId} categories={categories.data || []} session={session} />}
      {tab === 'disposals' && <Empty className="fixed-asset-empty" description="处置记录会在资产清理后显示，可从资产卡片详情进入清理向导" />}
    </Card>
  </Space>
}

function CategoryTab({ ledgerId, categories, session }: { ledgerId: string; categories: FixedAssetCategory[]; session: ReturnType<typeof useAuth>['session'] }) {
  const [open, setOpen] = useState(false); const [form] = Form.useForm(); const client = useQueryClient()
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session) })
  const save = useMutation({ mutationFn: (value: Record<string, unknown>) => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-asset-categories', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: value as FixedAssetCategoryCreate })), onSuccess: () => { setOpen(false); form.resetFields(); void client.invalidateQueries({ queryKey: ['fixed-asset-categories', ledgerId] }); message.success('类别已保存') }, onError: (error) => message.error(apiErrorMessage(error, '类别保存失败')) })
  const accountOptions = activeLeafAccountOptions(accounts.data || [])
  return <><div className="fixed-asset-list"><Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新增类别</Button><Table className="financial-table" rowKey="id" dataSource={categories} columns={[{ title: '编码', dataIndex: 'code' }, { title: '名称', dataIndex: 'name' }, { title: '期限（月）', dataIndex: 'usefulLifeMonths' }, { title: '残值率', dataIndex: 'residualRate', render: (v) => `${v}%` }, { title: '状态', dataIndex: 'status' }]} /></div><Modal open={open} title="新增资产类别" onCancel={() => setOpen(false)} onOk={() => form.submit()} confirmLoading={save.isPending}><Form form={form} layout="vertical" onFinish={(value) => save.mutate(value)}><Row gutter={12}><Col span={12}><Form.Item name="code" label="编码" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item name="usefulLifeMonths" label="默认期限（月）" rules={[{ required: true }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="residualRate" label="净残值率（%）" rules={[{ required: true }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item></Col></Row>{accountFields.map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={name === 'impairmentAccountId' ? undefined : [{ required: true }]}><Select allowClear showSearch optionFilterProp="label" options={accountOptions} /></Form.Item>)}</Form></Modal></>
}

function activeLeafAccountOptions(accounts: Account[]) {
  return accounts.filter((account) => account.isLeaf && account.status === 'ACTIVE').map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))
}

export function FixedAssetEditorPage() {
  const { ledgerId = '', assetId } = useParams(); const { session, periods, categories, dimensionTypes, departmentValues } = useLedgerData(ledgerId); const [form] = Form.useForm(); const [disposeForm] = Form.useForm(); const navigate = useNavigate(); const client = useQueryClient(); const [messageApi, contextHolder] = message.useMessage(); const [disposeOpen, setDisposeOpen] = useState(false)
  const currentPeriod = periods.data?.find((item) => item.status === 'OPEN')
  const asset = useQuery({ queryKey: ['fixed-asset', ledgerId, assetId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-assets/{assetId}', { params: { path: { ledgerId, assetId: assetId! }, query: { periodId: currentPeriod!.id } }, headers: apiHeaders(session!) })), enabled: Boolean(session && assetId && currentPeriod) })
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session && ledgerId) })
  useEffect(() => { if (!assetId) { form.resetFields(); disposeForm.resetFields(); setDisposeOpen(false) } }, [assetId, disposeForm, form])
  useEffect(() => { if (asset.data) form.setFieldsValue({ ...asset.data, serviceDate: dayjs(asset.data.serviceDate) }) }, [asset.data, form])
  const save = useMutation({ mutationFn: (values: Record<string, unknown>) => assetId
    ? apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}/fixed-assets/{assetId}', { params: { path: { ledgerId, assetId } }, headers: apiHeaders(session!), body: { ...fixedAssetPayload(values, true), expectedVersion: asset.data!.version!, changePeriodId: currentPeriod!.id, reason: '资产卡片维护' } }))
    : apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-assets', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: fixedAssetPayload(values, false) })), onSuccess: (saved) => { void client.invalidateQueries({ queryKey: ['fixed-assets', ledgerId] }); messageApi.success('资产卡片已保存'); navigate(`/ledgers/${ledgerId}/fixed-assets/${saved.id}`) }, onError: (error) => messageApi.error(apiErrorMessage(error, '资产卡片保存失败')) })
  const copy = useMutation({ mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-assets/{assetId}:copy', { params: { path: { ledgerId, assetId: assetId! } }, headers: apiHeaders(session!) })), onSuccess: (copied) => { messageApi.success('已创建副本'); void client.invalidateQueries({ queryKey: ['fixed-assets', ledgerId] }); navigate(`/ledgers/${ledgerId}/fixed-assets/${copied.id}`) }, onError: (error) => messageApi.error(apiErrorMessage(error, '复制资产卡片失败')) })
  const dispose = useMutation({ mutationFn: (values: DisposeFormValues) => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-assets/{assetId}:dispose', { params: { path: { ledgerId, assetId: assetId! } }, headers: apiHeaders(session!), body: { periodId: currentPeriod!.id, disposalDate: values.disposalDate!.format('YYYY-MM-DD'), reason: values.reason!, proceeds: String(values.proceeds ?? 0), outputTax: String(values.outputTax ?? 0), clearingCost: String(values.clearingCost ?? 0), clearingInputTax: String(values.clearingInputTax ?? 0), receiptAccountId: values.receiptAccountId, paymentAccountId: values.paymentAccountId, outputTaxAccountId: values.outputTaxAccountId, inputTaxAccountId: values.inputTaxAccountId } })), onSuccess: () => { setDisposeOpen(false); messageApi.success('资产已清理并生成凭证'); void client.invalidateQueries({ queryKey: ['fixed-asset', ledgerId, assetId] }); void client.invalidateQueries({ queryKey: ['fixed-assets', ledgerId] }) }, onError: (error) => messageApi.error(apiErrorMessage(error, '资产清理失败')) })
  const remove = useMutation({ mutationFn: () => apiData(openApiClient.DELETE('/v1/ledgers/{ledgerId}/fixed-assets/{assetId}', { params: { path: { ledgerId, assetId: assetId! } }, headers: apiHeaders(session!) })), onSuccess: () => { messageApi.success('资产卡片已删除'); void client.invalidateQueries({ queryKey: ['fixed-assets', ledgerId] }); navigate(`/ledgers/${ledgerId}/fixed-assets`) }, onError: (error) => messageApi.error(`${apiErrorMessage(error, '删除资产卡片失败')}；若资产已有历史，请先清理相关业务。`) })
  const categoryId = Form.useWatch('categoryId', form); const category = categories.data?.find((item) => item.id === categoryId)
  useEffect(() => { if (!assetId && category) form.setFieldsValue({ usefulLifeMonths: category.usefulLifeMonths, residualRate: Number(category.residualRate), assetAccountId: category.assetAccountId, accumulatedDepreciationAccountId: category.accumulatedDepreciationAccountId, depreciationExpenseAccountId: category.depreciationExpenseAccountId, impairmentAccountId: category.impairmentAccountId, clearingAccountId: category.clearingAccountId, disposalGainAccountId: category.disposalGainAccountId, disposalLossAccountId: category.disposalLossAccountId }) }, [assetId, category, form])
  const readonly = asset.data?.status === 'DISPOSED'
  const referenceFailed = asset.isError || periods.isError || categories.isError || accounts.isError || dimensionTypes.isError || departmentValues.isError
  const referenceLoading = periods.isLoading || categories.isLoading || accounts.isLoading || dimensionTypes.isLoading || departmentValues.isLoading || Boolean(assetId && asset.isLoading)
  const missingOpenPeriod = periods.isSuccess && !currentPeriod
  const changesDisabled = referenceFailed || referenceLoading || missingOpenPeriod || Boolean(assetId && !asset.data)
  const accountOptions = activeLeafAccountOptions(accounts.data || [])
  const selectedDepartment = Form.useWatch('departmentValueId', form)
  const departmentOptions = (departmentValues.data || []).filter((value) => value.status === 'ACTIVE' || value.id === selectedDepartment).map((value) => ({ value: value.id, label: `${value.code} ${value.name}` }))
  const deleteAsset = () => Modal.confirm({ title: '删除资产卡片', content: '删除后无法恢复。若资产已有历史，请先清理相关业务。', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => remove.mutateAsync() })
  return <>{contextHolder}<Space direction="vertical" size={16} style={{ width: '100%' }}><div className="page-heading"><div><Space align="center"><Typography.Title level={1}>{assetId ? '固定资产卡片' : '新增固定资产'}</Typography.Title>{assetId && <Tag className={`fixed-asset-status ${readonly ? 'is-disposed' : 'is-active'}`}>{readonly ? '已清理' : '正常使用'}</Tag>}</Space><Typography.Text type="secondary">资产清理后卡片将保持只读。</Typography.Text></div><Space wrap><Button onClick={() => navigate(`/ledgers/${ledgerId}/fixed-assets`)}>返回列表</Button>{assetId && <Button icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/fixed-assets/new`)}>新增</Button>}{assetId && <Button disabled={changesDisabled} loading={copy.isPending} onClick={() => copy.mutate()}>复制</Button>}{assetId && !readonly && <><Button disabled={changesDisabled} onClick={() => setDisposeOpen(true)}>清理</Button><Button danger disabled={changesDisabled} icon={<DeleteOutlined />} loading={remove.isPending} onClick={deleteAsset}>删除</Button></>}<Button type="primary" disabled={readonly || changesDisabled} loading={save.isPending} onClick={() => form.submit()}>保存</Button></Space></div>{referenceFailed && <Alert type="error" showIcon message="资产卡片依赖数据加载失败" description="无法安全确认资产、科目或辅助核算信息，请刷新后重试。" />}{missingOpenPeriod && <Alert type="warning" showIcon message="没有可用的开放期间" description="请先开放会计期间，再维护固定资产卡片。" />}<Card className="fixed-asset-editor-card" loading={asset.isLoading}><Form form={form} layout="vertical" onFinish={(value) => save.mutate(value)} disabled={readonly || changesDisabled}>
    <section aria-labelledby="asset-basic-title"><Typography.Title level={2} id="asset-basic-title" className="fixed-asset-section-title">基本信息</Typography.Title><Row gutter={[12, 0]}><Col xs={24} md={8}><Form.Item name="categoryId" label="资产类别" rules={[{ required: true }]}><Select disabled={Boolean(assetId)} options={(categories.data || []).map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="code" label="资产编码" rules={[{ required: true }]}><Input disabled={Boolean(assetId)} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="name" label="资产名称" rules={[{ required: true }]}><Input /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="serviceDate" label="开始使用日期" rules={[{ required: true }]}><DatePicker style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="departmentValueId" label="使用部门"><Select allowClear={!assetId} showSearch optionFilterProp="label" options={departmentOptions} placeholder="选择部门" /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="quantity" label="数量" initialValue={1}><InputNumber min={0.000001} style={{ width: '100%' }} /></Form.Item></Col></Row></section>
    <section aria-labelledby="asset-method-title"><Typography.Title level={2} id="asset-method-title" className="fixed-asset-section-title">折旧方式与科目</Typography.Title><Row gutter={[12, 0]}><Col xs={24} md={8}><Form.Item label="折旧方式"><Input value="平均年限法" readOnly /></Form.Item></Col>{accountFields.map(([name, label]) => <Col key={name} xs={24} md={8}><Form.Item name={name} label={label} rules={name === 'impairmentAccountId' ? undefined : [{ required: true }]}><Select allowClear={name === 'impairmentAccountId' ? !assetId : true} showSearch optionFilterProp="label" options={accountOptions} /></Form.Item></Col>)}</Row></section>
    <section aria-labelledby="asset-amount-title"><Typography.Title level={2} id="asset-amount-title" className="fixed-asset-section-title">原值、净值与折旧</Typography.Title><Row gutter={[12, 0]}><Col xs={24} md={8}><Form.Item name="originalCost" label="原值" rules={[{ required: true }]}><InputNumber min={0.01} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="inputTax" label="不含税进项税额" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="usefulLifeMonths" label="使用期限（月）" rules={[{ required: true }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="residualRate" label="残值率（%）" rules={[{ required: true }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="openingDepreciatedMonths" label="期初已折旧月数" initialValue={0}><InputNumber disabled={Boolean(assetId)} min={0} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="openingAccumulatedDepreciation" label="期初累计折旧" initialValue={0}><InputNumber disabled={Boolean(assetId)} min={0} style={{ width: '100%' }} /></Form.Item></Col><Col xs={24} md={8}><Form.Item name="impairmentAmount" label="期初减值余额" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col></Row>{asset.data && <Descriptions className="fixed-asset-amount-summary" size="small" bordered column={{ xs: 1, sm: 2, lg: 3 }} items={[{ key: 'currentDepreciation', label: '月折旧', children: money(asset.data.currentDepreciation) }, { key: 'currentAccumulatedDepreciation', label: '本期折旧', children: money(asset.data.currentAccumulatedDepreciation) }, { key: 'endingAccumulatedDepreciation', label: '期末累计折旧', children: money(asset.data.endingAccumulatedDepreciation) }, { key: 'openingNetValue', label: '期初净值', children: money(asset.data.openingNetValue) }, { key: 'endingNetValue', label: '期末净值', children: money(asset.data.endingNetValue) }, { key: 'residualAmount', label: '残值金额', children: money(asset.data.residualAmount) }]} />}</section>
    <section aria-labelledby="asset-note-title"><Typography.Title level={2} id="asset-note-title" className="fixed-asset-section-title">备注</Typography.Title><Form.Item name="note" label="备注"><Input.TextArea rows={2} /></Form.Item></section>
  </Form></Card></Space><Modal open={disposeOpen} title="资产清理向导" onCancel={() => setDisposeOpen(false)} onOk={() => disposeForm.submit()} confirmLoading={dispose.isPending}><Form form={disposeForm} layout="vertical" onFinish={(values) => dispose.mutate(values)}><Form.Item name="disposalDate" label="清理日期" rules={[{ required: true }]}><DatePicker style={{ width: '100%' }} /></Form.Item><Form.Item name="reason" label="清理原因" rules={[{ required: true }]}><Input.TextArea /></Form.Item><Row gutter={12}><Col span={12}><Form.Item name="proceeds" label="不含税收入" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="outputTax" label="销项税" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="clearingCost" label="清理费用" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="clearingInputTax" label="费用进项税" initialValue={0}><InputNumber min={0} style={{ width: '100%' }} /></Form.Item></Col></Row>{[['receiptAccountId', '收款科目'], ['paymentAccountId', '付款科目'], ['outputTaxAccountId', '销项税科目'], ['inputTaxAccountId', '进项税科目']].map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={accountOptions} /></Form.Item>)}</Form></Modal></>
}
