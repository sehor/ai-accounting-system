import { Alert, App, Button, Card, Checkbox, Form, Input, Modal, Select, Space, Table, Tag, Tooltip, Upload } from 'antd'
import { DownloadOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { apiData, apiHeaders, openApiClient, ApiError, type ApiAuth } from '../api/client'
import type { components } from '../api/generated'

type Account = components['schemas']['Account']
type AccountImportPreview = components['schemas']['AccountImportPreview']
type CashFlowItem = components['schemas']['LedgerCashFlowItem']
type DimensionType = components['schemas']['DimensionType']
type Period = Omit<components['schemas']['Period'], 'hasVouchers'> & { hasVouchers?: boolean }
type AccountImportAction = 'CREATE' | 'UPDATE' | 'MAP' | 'SKIP'

type AccountTree = Account & { children?: AccountTree[] }
export type AccountCategoryTab =
  | 'CURRENT_ASSET' | 'NON_CURRENT_ASSET'
  | 'CURRENT_LIABILITY' | 'NON_CURRENT_LIABILITY'
  | 'EQUITY' | 'COST'
  | 'OPERATING_REVENUE' | 'OTHER_INCOME'
  | 'OPERATING_COST_AND_TAX' | 'OTHER_EXPENSE' | 'PERIOD_EXPENSE'
  | 'INCOME_TAX' | 'PRIOR_YEAR_ADJUSTMENT'

export const ACCOUNT_CATEGORY_LABELS: Record<AccountCategoryTab, string> = {
  CURRENT_ASSET: '流动资产',
  NON_CURRENT_ASSET: '非流动资产',
  CURRENT_LIABILITY: '流动负债',
  NON_CURRENT_LIABILITY: '非流动负债',
  EQUITY: '所有者权益',
  COST: '成本',
  OPERATING_REVENUE: '营业收入',
  OTHER_INCOME: '其他收益',
  OPERATING_COST_AND_TAX: '营业成本及税金',
  OTHER_EXPENSE: '其他损失',
  PERIOD_EXPENSE: '期间费用',
  INCOME_TAX: '所得税',
  PRIOR_YEAR_ADJUSTMENT: '以前年度损益调整',
}
type AccountForm = {
  code: string
  name: string
  standardAccountKey?: string
  category: string
  normalBalance: string
  cashFlowRequired?: boolean
  defaultCashFlowItemId?: string
  quantityEnabled?: boolean
  unitName?: string
  dimensionTypeIds?: string[]
  requiredDimensionTypeIds?: string[]
}

export function AccountsTab({ ledgerId, session, accounts, dimensionTypes, periods, loading, writable, onChanged, category }: {
  ledgerId: string
  session: ApiAuth
  accounts: Account[]
  dimensionTypes: DimensionType[]
  periods: Period[]
  loading: boolean
  writable: boolean
  onChanged: () => void
  category: AccountCategoryTab
}) {
  const { message, modal } = App.useApp()
  const [form] = Form.useForm<AccountForm>()
  const [editing, setEditing] = useState<Account | null>(null)
  const [parent, setParent] = useState<Account | null>(null)
  const [formOpen, setFormOpen] = useState(false)
  const [search, setSearch] = useState('')
  const [status, setStatus] = useState<string>()
  const [format, setFormat] = useState<'STANDARD' | 'KINGDEE'>('STANDARD')
  const [createdInPeriodId, setCreatedInPeriodId] = useState<string>()
  const [preview, setPreview] = useState<AccountImportPreview | null>(null)
  const [expandedRowKeys, setExpandedRowKeys] = useState<string[]>([])
  const selectedCategory = Form.useWatch('category', form)
  const standardKeyOptions = useMemo(() => {
    const byKey = new Map<string, Account>()
    accounts.forEach((account) => {
      if (account.standardAccountKey && (!selectedCategory || account.category === selectedCategory) &&
          !byKey.has(account.standardAccountKey)) {
        byKey.set(account.standardAccountKey, account)
      }
    })
    return [...byKey.entries()].sort(([left], [right]) => left.localeCompare(right)).map(([key, account]) => ({
      value: key,
      label: `${key} · ${account.code} ${account.name}`,
    }))
  }, [accounts, selectedCategory])
  useEffect(() => {
    if (!formOpen) return
    if (editing) {
      form.setFieldsValue({
        ...editing,
        standardAccountKey: editing.standardAccountKey || undefined,
        defaultCashFlowItemId: editing.defaultCashFlowItemId || undefined,
        unitName: editing.unitName || undefined,
        dimensionTypeIds: editing.dimensionRequirements.map((item) => item.dimensionTypeId),
        requiredDimensionTypeIds: editing.dimensionRequirements
          .filter((item) => item.required).map((item) => item.dimensionTypeId),
      })
      return
    }
    form.resetFields()
    form.setFieldsValue(parent ? {
      category: parent.category,
      normalBalance: parent.normalBalance,
    } : { category, cashFlowRequired: false, quantityEnabled: false })
  }, [category, editing, form, formOpen, parent])
  const cashFlowItems = useQuery({
    queryKey: ['cash-flow-items', ledgerId],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/cash-flow-items', { params: { path: { ledgerId } }, headers: apiHeaders(session) })),
  })
  const createdAtPeriod = periods.find((period) => period.id === createdInPeriodId)
  const tree = useMemo(() => filterTree(
    buildTree(accounts.filter((account) => matchesCategory(account, category))),
    (account) => (!createdAtPeriod || wasCreatedInPeriod(account.createdAt, createdAtPeriod)) &&
      (!status || account.status === status) &&
      (!search.trim() || `${account.code} ${account.name}`.toLowerCase().includes(search.trim().toLowerCase())),
  ), [accounts, category, createdAtPeriod, search, status])
  const filtering = Boolean(createdAtPeriod || status || search.trim())
  const visibleExpandedKeys = filtering ? expandedKeysFor(tree) : expandedRowKeys

  const save = useMutation({
    mutationFn: (value: AccountForm) => {
      const dimensionRequirements = (value.dimensionTypeIds || []).map((dimensionTypeId) => ({
        dimensionTypeId,
        required: (value.requiredDimensionTypeIds || []).includes(dimensionTypeId),
      }))
      const { dimensionTypeIds: _dimensionTypeIds, requiredDimensionTypeIds: _requiredDimensionTypeIds,
        standardAccountKey, ...fields } = value
      const body = {
        ...fields,
        parentId: editing?.parentId || parent?.id || undefined,
        dimensionRequirements,
      }
      return editing
        ? apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}/accounts/{accountId}', {
            params: { path: { ledgerId, accountId: editing.id } }, headers: apiHeaders(session),
            body: { ...body, expectedVersion: editing.version },
          }))
        : apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/accounts', {
            params: { path: { ledgerId } }, headers: apiHeaders(session),
            body: { ...body, standardAccountKey: parent ? undefined : standardAccountKey },
          }))
    },
    onSuccess: () => {
      message.success(editing ? '科目已更新' : '科目已创建')
      setFormOpen(false)
      onChanged()
    },
    onError: (error) => message.error(errorText(error)),
  })

  const patchStatus = useMutation({
    mutationFn: ({ account, next }: { account: Account; next: 'ACTIVE' | 'INACTIVE' }) =>
      apiData(openApiClient.PATCH('/v1/ledgers/{ledgerId}/accounts/{accountId}', {
        params: { path: { ledgerId, accountId: account.id } }, headers: apiHeaders(session),
        body: { expectedVersion: account.version, status: next },
      })),
    onSuccess: () => onChanged(),
    onError: (error) => message.error(errorText(error)),
  })

  const remove = useMutation({
    mutationFn: (account: Account) => apiData(openApiClient.DELETE('/v1/ledgers/{ledgerId}/accounts/{accountId}', {
      params: { path: { ledgerId, accountId: account.id }, query: { expectedVersion: account.version } }, headers: apiHeaders(session),
    })),
    onSuccess: () => { message.success('科目已删除'); onChanged() },
    onError: (error) => message.error(errorText(error)),
  })

  const upload = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData()
      body.append('file', file)
      return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/account-imports', {
        params: { path: { ledgerId }, query: { format } }, headers: apiHeaders(session),
        body: { file: file as unknown as string }, bodySerializer: () => body,
      }))
    },
    onSuccess: setPreview,
    onError: (error) => message.error(errorText(error)),
  })

  const decide = useMutation({
    mutationFn: ({ rowNo, action, targetAccountId }: {
      rowNo: number
      action: 'CREATE' | 'UPDATE' | 'MAP' | 'SKIP'
      targetAccountId: string | null
    }) => apiData(openApiClient.PUT('/v1/ledgers/{ledgerId}/account-imports/{importId}/rows/{rowNo}', {
      params: { path: { ledgerId, importId: preview!.id, rowNo } }, headers: apiHeaders(session),
      body: { action, targetAccountId, accountCode: null },
    })),
    onSuccess: setPreview,
    onError: (error) => message.error(errorText(error)),
  })

  const commit = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/account-imports/{importId}:commit', {
      params: { path: { ledgerId, importId: preview!.id } }, headers: apiHeaders(session),
    })),
    onSuccess: (result) => {
      setPreview(result)
      message.success('科目已原子提交')
      onChanged()
    },
    onError: (error) => message.error(errorText(error)),
  })

  const openCreate = (parentAccount: Account | null) => {
    setEditing(null)
    setParent(parentAccount)
    setFormOpen(true)
  }

  const openEdit = (account: Account) => {
    setEditing(account)
    setParent(null)
    setFormOpen(true)
  }

  const download = async (kind: 'account-import-template' | 'account-export') => {
    try {
      const options = { params: { path: { ledgerId }, query: { format, createdInPeriodId: createdInPeriodId || undefined } }, headers: apiHeaders(session), parseAs: 'blob' as const }
      const blob = await apiData(kind === 'account-export'
        ? openApiClient.GET('/v1/ledgers/{ledgerId}/account-export', options)
        : openApiClient.GET('/v1/ledgers/{ledgerId}/account-import-template', options)) as unknown as Blob
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `${kind}-${format.toLowerCase()}.xlsx`
      anchor.click()
      URL.revokeObjectURL(url)
    } catch (error) {
      message.error(errorText(error))
    }
  }

  return <Space direction="vertical" size={12} style={{ width: '100%' }}>
    <Card>
      <Space wrap>
        <Input.Search allowClear placeholder="搜索编码或名称" value={search} onChange={(event) => setSearch(event.target.value)} style={{ width: 240 }} />
        <Select allowClear placeholder="状态" onChange={setStatus} style={{ width: 120 }}
          options={[{ value: 'ACTIVE', label: '启用' }, { value: 'INACTIVE', label: '停用' }]} />
        {writable && <Button type="primary" icon={<PlusOutlined />} onClick={() => openCreate(null)}>新增一级科目</Button>}
        <Select value={format} onChange={setFormat} style={{ width: 120 }}
          options={[{ value: 'STANDARD', label: '标准格式' }, { value: 'KINGDEE', label: '金蝶格式' }]} />
        <Tooltip title="仅导出在所选会计期间创建的科目">
          <span>Created at</span>
        </Tooltip>
        <Select allowClear aria-label="Created at" placeholder="全部期间" value={createdInPeriodId}
          onChange={setCreatedInPeriodId} style={{ width: 230 }}
          options={periods.map((period) => ({
            value: period.id,
            label: `${period.periodCode}（${period.startDate} ~ ${period.endDate}）`,
          }))} />
        <Button icon={<DownloadOutlined />} onClick={() => void download('account-import-template')}>下载模板</Button>
        <Button icon={<DownloadOutlined />} onClick={() => void download('account-export')}>导出</Button>
        {writable && <Upload accept={format === 'KINGDEE' ? '.xls,.xlsx' : '.xlsx'} showUploadList={false} beforeUpload={(file) => {
          const name = file.name.toLowerCase()
          if ((!name.endsWith('.xlsx') && (format !== 'KINGDEE' || !name.endsWith('.xls'))) || file.size > 10 * 1024 * 1024) {
            message.error(format === 'KINGDEE' ? '仅支持不超过 10 MiB 的 .xls/.xlsx 文件' : '仅支持不超过 10 MiB 的 .xlsx 文件')
            return Upload.LIST_IGNORE
          }
          upload.mutate(file)
          return false
        }}>
          <Button icon={<UploadOutlined />} loading={upload.isPending}>上传预检</Button>
        </Upload>}
      </Space>
    </Card>
    {accounts.some((account) => account.legacyCode) &&
      <Alert showIcon type="warning" message="存在无法自动推断层级的遗留编码；可继续记账，但已使用科目的核心属性会锁定。" />}
    <Table<AccountTree>
      rowKey="id"
      loading={loading}
      dataSource={tree}
      pagination={false}
      expandable={{
        expandedRowKeys: visibleExpandedKeys,
        onExpandedRowsChange: (keys) => setExpandedRowKeys(keys.map(String)),
      }}
      columns={[
        { title: '编码', dataIndex: 'code', render: (value, account) =>
          <Space>{value}{account.legacyCode && <Tag color="warning">遗留</Tag>}</Space> },
        { title: '名称', dataIndex: 'name' },
        { title: '类别 / 余额方向', render: (_, account) =>
          `${ACCOUNT_CATEGORY_LABELS[account.category as AccountCategoryTab] || account.category} / ${account.normalBalance === 'CREDIT' ? '贷' : '借'}` },
        { title: '控制', render: (_, account) => <Space wrap>
          {account.cashFlowRequired && <Tag>现金流</Tag>}
          {account.quantityEnabled && <Tag>数量：{account.unitName}</Tag>}
          {account.dimensionRequirements.map((item) =>
            <Tag key={item.dimensionTypeId}>{item.name}{item.required ? '*' : ''}</Tag>)}
        </Space> },
        { title: '状态', render: (_, account) =>
          <Tag color={account.status === 'ACTIVE' ? 'green' : 'default'}>{account.status}</Tag> },
        ...(writable ? [{ title: '操作', render: (_: unknown, account: AccountTree) => <Space wrap>
          <Button size="small" onClick={() => openEdit(account)}>编辑</Button>
          {account.level < 4 && <Tooltip title={account.hasBusinessUsage ? '已有业务，不能再新增子科目' : ''}>
            <Button size="small" disabled={account.hasBusinessUsage} onClick={() => openCreate(account)}>新增子科目</Button>
          </Tooltip>}
          <Button size="small" onClick={() => patchStatus.mutate({
            account, next: account.status === 'ACTIVE' ? 'INACTIVE' : 'ACTIVE',
          })}>{account.status === 'ACTIVE' ? '停用' : '启用'}</Button>
          <Button size="small" danger disabled={account.isTemplate || !account.isLeaf || account.hasBusinessUsage}
            onClick={() => modal.confirm({
              title: `删除科目 ${account.code}？`,
              content: '删除后不可恢复。',
              okType: 'danger',
              onOk: () => remove.mutateAsync(account),
            })}>删除</Button>
        </Space> }] : []),
      ]}
    />
    <Modal title={editing ? `编辑 ${editing.code}` : parent ? `新增 ${parent.code} 的子科目` : '新增一级科目'}
      open={formOpen} onCancel={() => setFormOpen(false)} onOk={() => form.submit()}
      confirmLoading={save.isPending} destroyOnHidden>
      <Form form={form} layout="vertical" onFinish={(value) => save.mutate(value)}>
        {editing?.coreLocked && <Alert type="info" showIcon message="已有已记账凭证或已确认期初余额，仅名称和状态可修改。" />}
        <Form.Item name="code" label="科目编码" rules={[{ required: true }, { max: 32 }]}>
          <Input disabled={Boolean(editing?.coreLocked || editing?.isTemplate)} />
        </Form.Item>
        <Form.Item name="name" label="科目名称" rules={[{ required: true }, { max: 200 }]}><Input /></Form.Item>
        {!editing && !parent && <Form.Item name="standardAccountKey" label="法定报表归类"
          extra="稳定归类不会随科目名称或编码修改"
          rules={[{ required: true, message: '请选择法定报表归类' }]}>
          <Select showSearch optionFilterProp="label" options={standardKeyOptions}
            placeholder="选择一个已安装的准则科目归类" />
        </Form.Item>}
        <Space style={{ width: '100%' }} align="start">
          <Form.Item name="category" label="类别" rules={[{ required: true }]}>
            <Select disabled={Boolean(parent || editing?.parentId || editing?.coreLocked || editing?.isTemplate)}
              style={{ width: 180 }} options={Object.entries(ACCOUNT_CATEGORY_LABELS)
                .map(([value, label]) => ({ value, label }))} />
          </Form.Item>
          <Form.Item name="normalBalance" label="余额方向" rules={[{ required: true }]}>
            <Select disabled={Boolean(editing)}
              style={{ width: 150 }} options={[{ value: 'DEBIT', label: '借' }, { value: 'CREDIT', label: '贷' }]} />
          </Form.Item>
        </Space>
        <Form.Item name="cashFlowRequired" valuePropName="checked"><Checkbox disabled={editing?.coreLocked}>现金流项目必填</Checkbox></Form.Item>
        <Form.Item name="defaultCashFlowItemId" label="默认现金流项目">
          <Select allowClear disabled={editing?.coreLocked} options={(cashFlowItems.data || [])
            .map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} />
        </Form.Item>
        <Form.Item name="quantityEnabled" valuePropName="checked"><Checkbox disabled={editing?.coreLocked}>启用数量金额核算</Checkbox></Form.Item>
        <Form.Item noStyle shouldUpdate={(before, after) => before.quantityEnabled !== after.quantityEnabled}>
          {({ getFieldValue }) => getFieldValue('quantityEnabled') &&
            <Form.Item name="unitName" label="固定单位" rules={[{ required: true }, { max: 64 }]}>
              <Input disabled={editing?.coreLocked} />
            </Form.Item>}
        </Form.Item>
        <Form.Item name="dimensionTypeIds" label="辅助核算类型">
          <Select mode="multiple" disabled={editing?.coreLocked} options={dimensionTypes
            .map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} />
        </Form.Item>
        <Form.Item name="requiredDimensionTypeIds" label="其中必填的辅助类型">
          <Select mode="multiple" disabled={editing?.coreLocked} options={dimensionTypes
            .map((item) => ({ value: item.id, label: `${item.code} ${item.name}` }))} />
        </Form.Item>
      </Form>
    </Modal>
    <Modal title={`科目导入预检：${preview?.filename || ''}`} width={1000}
      open={Boolean(preview)} onCancel={() => setPreview(null)}
      footer={<Space>
        <Button onClick={() => setPreview(null)}>关闭</Button>
        <Button type="primary" loading={commit.isPending}
          disabled={!preview || preview.status === 'COMMITTED' || preview.rows.some((row) => !row.confirmed)}
          onClick={() => commit.mutate()}>原子提交</Button>
      </Space>}>
      {preview && <><Alert showIcon type={preview.errorCount ? 'error' : 'info'}
        message={`${preview.rowCount} 行，${preview.errorCount} 个阻断错误；AI：${preview.aiStatus}`} />
      <Table rowKey="rowNo" size="small" pagination={{ pageSize: 20 }} dataSource={preview.rows} columns={[
        { title: '行', dataIndex: 'rowNo', width: 60 },
        { title: '编码', dataIndex: 'accountCode' },
        { title: '名称', render: (_, row) => row.cleanedData.name },
        { title: '置信度', dataIndex: 'confidence' },
        { title: '问题', render: (_, row) => row.issues.map((issue) => <Tag color="red" key={issue}>{issue}</Tag>) },
        { title: '处理', render: (_, row) => <Select value={row.confirmed ? row.action as AccountImportAction | null : undefined}
          placeholder={`建议：${row.action}`} style={{ width: 130 }}
          onChange={(action: AccountImportAction) => decide.mutate({
            rowNo: row.rowNo,
            action,
            targetAccountId: ['MAP', 'UPDATE'].includes(action) ? row.targetAccountId : null,
          })}
          options={(row.targetAccountId
            ? ['UPDATE', 'MAP', 'SKIP']
            : ['CREATE', 'SKIP']).map((value) => ({ value, label: value }))} /> },
      ]} /></>}
    </Modal>
  </Space>
}

function buildTree(accounts: Account[]): AccountTree[] {
  const nodes = new Map(accounts.map((account) => [account.id, { ...account } as AccountTree]))
  const roots: AccountTree[] = []
  for (const node of nodes.values()) {
    const parent = node.parentId ? nodes.get(node.parentId) : undefined
    if (parent) (parent.children ||= []).push(node)
    else roots.push(node)
  }
  const sort = (rows: AccountTree[]) => rows.sort((a, b) => a.code.localeCompare(b.code))
    .forEach((row) => row.children && sort(row.children))
  sort(roots)
  return roots
}

function matchesCategory(account: Account, category: AccountCategoryTab) {
  return account.category === category
}

function wasCreatedInPeriod(createdAt: string | null, period: Period) {
  if (!createdAt) return false
  const timestamp = Date.parse(createdAt)
  const start = Date.parse(`${period.startDate}T00:00:00+08:00`)
  const end = Date.parse(`${period.endDate}T00:00:00+08:00`) + 24 * 60 * 60 * 1000
  return Number.isFinite(timestamp) && timestamp >= start && timestamp < end
}

function filterTree(nodes: AccountTree[], matches: (account: Account) => boolean): AccountTree[] {
  return nodes.flatMap((node) => {
    const children = filterTree(node.children || [], matches)
    return matches(node) || children.length ? [{ ...node, children: children.length ? children : undefined }] : []
  })
}

function expandedKeysFor(nodes: AccountTree[]): string[] {
  return nodes.flatMap((node) => node.children?.length ? [node.id, ...expandedKeysFor(node.children)] : [])
}

function errorText(error: unknown): string {
  return error instanceof ApiError ? error.message : '操作失败，请稍后重试'
}
