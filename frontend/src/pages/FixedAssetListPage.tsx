import { PlusOutlined, ReloadOutlined, UploadOutlined } from '@ant-design/icons'
import { Alert, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Space, Table, Tabs, Tag, Typography, Upload, message } from 'antd'
import type { ColumnsType } from 'antd/es/table'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, apiData, apiHeaders, openApiClient } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

type Account = components['schemas']['Account']
type Category = components['schemas']['FixedAssetCategory']
type CategoryCreate = components['schemas']['CategoryCreate']
type Asset = components['schemas']['FixedAsset']
type DimensionValue = components['schemas']['LedgerDimensionValue']

const tabs = ['cards', 'categories', 'disposals'] as const
const accountFields = [
  ['assetAccountId', '固定资产科目'],
  ['accumulatedDepreciationAccountId', '累计折旧科目'],
  ['depreciationExpenseAccountId', '折旧费用科目'],
  ['impairmentAccountId', '减值准备科目'],
  ['clearingAccountId', '固定资产清理科目'],
  ['disposalGainAccountId', '处置收益科目'],
  ['disposalLossAccountId', '处置损失科目'],
] as const

function normalizeTab(value: string | null) {
  return tabs.includes(value as (typeof tabs)[number]) ? value as (typeof tabs)[number] : 'cards'
}

function errorMessage(error: unknown, fallback: string) {
  return error instanceof ApiError ? error.message : fallback
}

function money(value: string | number | null | undefined) {
  return value == null ? '-' : Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

function useReferenceData(ledgerId: string) {
  const { session } = useAuth()
  const headers = apiHeaders(session!)
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/periods', { params: { path: { ledgerId } }, headers })), enabled: Boolean(session && ledgerId) })
  const categories = useQuery({ queryKey: ['fixed-asset-categories', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-asset-categories', { params: { path: { ledgerId } }, headers })), enabled: Boolean(session && ledgerId) })
  const types = useQuery({ queryKey: ['dimension-types', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types', { params: { path: { ledgerId } }, headers })), enabled: Boolean(session && ledgerId) })
  const departmentType = types.data?.find((type) => type.code === 'DEPARTMENT')
  const departments = useQuery({
    queryKey: ['dimension-values', ledgerId, departmentType?.id],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/dimension-types/{typeId}/values', { params: { path: { ledgerId, typeId: departmentType!.id } }, headers })),
    enabled: Boolean(session && ledgerId && departmentType),
  })
  return { session, periods, categories, types, departments }
}

async function downloadTemplate(ledgerId: string, headers: HeadersInit) {
  return apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-assets/import-template', {
    params: { path: { ledgerId } }, headers, parseAs: 'blob',
  })) as unknown as Blob
}

async function importWorkbook(ledgerId: string, headers: HeadersInit, file: File) {
  const body = new FormData()
  body.append('file', file)
  return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-assets/import', {
    params: { path: { ledgerId } }, headers, body: { file: file as unknown as string }, bodySerializer: () => body,
  }))
}

export function FixedAssetListPage() {
  const { ledgerId = '' } = useParams()
  const { session, periods, categories, types, departments } = useReferenceData(ledgerId)
  const navigate = useNavigate()
  const [search, setSearch] = useWorkspaceSearchParams()
  const tab = normalizeTab(search.get('tab'))
  const period = periods.data?.find((item) => item.status === 'OPEN') || periods.data?.at(-1)
  const categoryId = search.get('categoryId') || undefined
  const keyword = search.get('keyword') || undefined
  const assets = useQuery({
    queryKey: ['fixed-assets', ledgerId, period?.id, categoryId, keyword],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/fixed-assets', {
      params: { path: { ledgerId }, query: { periodId: period!.id, page: 1, pageSize: 100, categoryId, search: keyword } },
      headers: apiHeaders(session!),
    })),
    enabled: Boolean(session && ledgerId && period),
  })
  const referenceFailed = periods.isError || categories.isError || types.isError || departments.isError || assets.isError
  const departmentNames = useMemo(() => new Map((departments.data || []).map((value: DimensionValue) => [value.id, value.name])), [departments.data])
  const columns: ColumnsType<Asset> = [
    { title: '编号', dataIndex: 'code', fixed: 'left', render: (value, row) => <Link to={`/ledgers/${ledgerId}/fixed-assets/${row.id}`}>{value}</Link> },
    { title: '名称', dataIndex: 'name' }, { title: '类别', dataIndex: 'categoryName' },
    { title: '部门', dataIndex: 'departmentValueId', render: (value: string | null) => value ? departmentNames.get(value) || '-' : '-' },
    { title: '原值', dataIndex: 'originalCost', render: money }, { title: '本期折旧', dataIndex: 'currentAccumulatedDepreciation', render: money },
    { title: '期末累计折旧', dataIndex: 'endingAccumulatedDepreciation', render: money }, { title: '月折旧', dataIndex: 'currentDepreciation', render: money },
    { title: '期末净值', dataIndex: 'endingNetValue', render: money },
    { title: '状态', dataIndex: 'status', render: (value) => <Tag color={value === 'ACTIVE' ? 'green' : 'default'}>{value === 'ACTIVE' ? '正常使用' : '已清理'}</Tag> },
  ]
  const changeTab = (value: string) => {
    const next = new URLSearchParams(search)
    next.set('tab', normalizeTab(value))
    setSearch(next)
  }
  const headers = apiHeaders(session!)

  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>固定资产</Typography.Title><Typography.Text type="secondary">资产卡片、类别与处置记录</Typography.Text></div><Space wrap>
      <Button icon={<ReloadOutlined />} onClick={() => assets.refetch()}>刷新</Button>
      <Button onClick={async () => { try { const blob = await downloadTemplate(ledgerId, headers); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'fixed-assets-template.xlsx'; link.click(); URL.revokeObjectURL(url) } catch (error) { message.error(errorMessage(error, '下载模板失败')) } }}>下载模板</Button>
      <Upload disabled={referenceFailed} accept=".xlsx" showUploadList={false} beforeUpload={async (file) => { try { const result = await importWorkbook(ledgerId, headers, file); if (result.committed) { message.success(`已导入 ${result.rowCount} 张卡片`); await assets.refetch() } else message.error(result.errors.join('；')) } catch (error) { message.error(errorMessage(error, '导入失败')) } return false }}><Button disabled={referenceFailed} icon={<UploadOutlined />}>导入 Excel</Button></Upload>
      <Button disabled={referenceFailed} type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/fixed-assets/new`)}>新增卡片</Button>
    </Space></div>
    {referenceFailed && <Alert type="error" showIcon message="固定资产列表加载失败" description="请刷新重试；依赖数据恢复前已禁用导入和编辑。" />}
    <Tabs activeKey={tab} onChange={changeTab} items={[
      { key: 'cards', label: '资产卡片', children: <div className="fixed-asset-list"><Space wrap><Select allowClear placeholder="全部类别" value={categoryId} style={{ width: 180 }} options={(categories.data || []).map((item) => ({ value: item.id, label: item.name }))} onChange={(value) => { const next = new URLSearchParams(search); value ? next.set('categoryId', value) : next.delete('categoryId'); setSearch(next) }} /><Input.Search allowClear placeholder="编号或名称" defaultValue={keyword} style={{ width: 240 }} onSearch={(value) => { const next = new URLSearchParams(search); value ? next.set('keyword', value) : next.delete('keyword'); setSearch(next) }} /></Space><Table className="financial-table" rowKey="id" dataSource={assets.data?.data || []} columns={columns} pagination={false} scroll={{ x: 1350 }} locale={{ emptyText: '暂无固定资产卡片' }} /></div> },
      { key: 'categories', label: '资产类别', children: <CategorySettings ledgerId={ledgerId} categories={categories.data || []} /> },
      { key: 'disposals', label: '处置记录', children: <Alert type="info" showIcon message="处置记录可在资产卡片中查看" /> },
    ]} />
  </Space>
}

function CategorySettings({ ledgerId, categories }: { ledgerId: string; categories: Category[] }) {
  const { session } = useAuth()
  const client = useQueryClient()
  const [open, setOpen] = useState(false)
  const [form] = Form.useForm<CategoryCreate>()
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { params: { path: { ledgerId } }, headers: apiHeaders(session!) })), enabled: Boolean(session) })
  const save = useMutation({
    mutationFn: (value: CategoryCreate) => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/fixed-asset-categories', { params: { path: { ledgerId } }, headers: apiHeaders(session!), body: { ...value, residualRate: String(value.residualRate) } })),
    onSuccess: () => { setOpen(false); form.resetFields(); void client.invalidateQueries({ queryKey: ['fixed-asset-categories', ledgerId] }); message.success('类别已保存') },
    onError: (error) => message.error(errorMessage(error, '类别保存失败')),
  })
  const accountOptions = (accounts.data || []).filter((account: Account) => account.isLeaf && account.status === 'ACTIVE').map((account: Account) => ({ value: account.id, label: `${account.code} ${account.name}` }))
  return <><div className="fixed-asset-list"><Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新增类别</Button><Table className="financial-table" rowKey="id" dataSource={categories} columns={[{ title: '编码', dataIndex: 'code' }, { title: '名称', dataIndex: 'name' }, { title: '期限（月）', dataIndex: 'usefulLifeMonths' }, { title: '残值率', dataIndex: 'residualRate', render: (value) => `${value}%` }, { title: '状态', dataIndex: 'status' }]} /></div>
    <Modal open={open} title="新增资产类别" onCancel={() => setOpen(false)} onOk={() => form.submit()} confirmLoading={save.isPending}><Form form={form} layout="vertical" onFinish={save.mutate}><Row gutter={12}><Col span={12}><Form.Item name="code" label="编码" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item name="name" label="名称" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={12}><Form.Item name="usefulLifeMonths" label="默认期限（月）" rules={[{ required: true }]}><InputNumber min={1} style={{ width: '100%' }} /></Form.Item></Col><Col span={12}><Form.Item name="residualRate" label="净残值率（%）" rules={[{ required: true }]}><InputNumber min={0} max={100} style={{ width: '100%' }} /></Form.Item></Col></Row>{accountFields.map(([name, label]) => <Form.Item key={name} name={name} label={label} rules={name === 'impairmentAccountId' ? undefined : [{ required: true }]}><Select allowClear showSearch optionFilterProp="label" options={accountOptions} /></Form.Item>)}</Form></Modal>
  </>
}
