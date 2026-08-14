import { Alert, Card, Empty, Input, Pagination, Table, Tree, Typography } from 'antd'
import type { DataNode } from 'antd/es/tree'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Account, GeneralLedgerAccount, GeneralLedgerPage, SubLedgerEntry, SubLedgerPage, TrialBalanceLine } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { PeriodRangeSelector, usePeriodRangeFilter } from '../components/PeriodSelector'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

const bookNames: Record<string, string> = {
  'trial-balance': '科目余额表',
  'general-ledger': '总账',
  'sub-ledger': '明细账',
}

const money = (value?: string) => value && Number(value) !== 0
  ? Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  : ''
const direction = (value: string) => value === 'CREDIT' ? '贷' : '借'

export function BooksPage() {
  const { bookType = 'trial-balance' } = useParams()
  if (bookType === 'general-ledger') return <GeneralLedgerPageView />
  if (bookType === 'sub-ledger') return <SubLedgerPageView />
  return <TrialBalancePage />
}

function BookToolbar({ title, refreshing, onRefresh }: { title: string; refreshing: boolean; onRefresh: () => void }) {
  const { ledgerId = '' } = useParams()
  const { periods, periodFrom, periodTo, setPeriodRange } = usePeriodRangeFilter(ledgerId)
  return <div className="financial-toolbar">
    <Typography.Title level={1}>{title}</Typography.Title>
    <PeriodRangeSelector
      periodFrom={periodFrom}
      periodTo={periodTo}
      periods={periods.data || []}
      loading={periods.isLoading}
      refreshing={refreshing}
      onChange={setPeriodRange}
      onRefresh={onRefresh}
    />
  </div>
}

function TrialBalancePage() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const { periodFrom, periodTo } = usePeriodRangeFilter(ledgerId)
  const query = useQuery({
    queryKey: ['report', ledgerId, 'trial-balance', periodFrom, periodTo],
    queryFn: () => apiFetch<TrialBalanceLine[]>(
      `/ledgers/${ledgerId}/reports/trial-balance?periodFrom=${periodFrom}&periodTo=${periodTo}`, session!,
    ),
    enabled: Boolean(session && ledgerId && periodFrom && periodTo),
  })
  return <section className="financial-page">
    <BookToolbar title={bookNames['trial-balance']} refreshing={query.isFetching} onRefresh={() => void query.refetch()} />
    {query.isError && <Alert type="error" showIcon message="科目余额表读取失败" />}
    <Card className="financial-grid-card"><Table
      rowKey="accountId" size="small" className="financial-table" loading={query.isLoading || !periodFrom || !periodTo}
      dataSource={query.data || []} locale={{ emptyText: <Empty description="当前期间暂无余额" /> }}
      pagination={false} scroll={{ x: 1320 }} columns={[
        { title: '科目编码', dataIndex: 'code', width: 150 },
        { title: '科目名称', dataIndex: 'name', width: 320 },
        { title: '期初借方', dataIndex: 'openingDebit', width: 150, align: 'right', render: money },
        { title: '期初贷方', dataIndex: 'openingCredit', width: 150, align: 'right', render: money },
        { title: '发生借方', dataIndex: 'periodDebit', width: 150, align: 'right', render: money },
        { title: '发生贷方', dataIndex: 'periodCredit', width: 150, align: 'right', render: money },
        { title: '期末借方', dataIndex: 'closingDebit', width: 150, align: 'right', render: money },
        { title: '期末贷方', dataIndex: 'closingCredit', width: 150, align: 'right', render: money },
      ]} />
    </Card>
  </section>
}

type GeneralLedgerDisplayRow = GeneralLedgerAccount & { rowType: 'opening' | 'period' | 'year'; summary: string }

function GeneralLedgerPageView() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const { periodFrom, periodTo } = usePeriodRangeFilter(ledgerId)
  const page = Number(search.get('page') || 1)
  const query = useQuery({
    queryKey: ['book', ledgerId, 'general-ledger', periodFrom, periodTo, page],
    queryFn: () => apiFetch<GeneralLedgerPage>(
      `/ledgers/${ledgerId}/books/general-ledger?periodFrom=${periodFrom}&periodTo=${periodTo}&page=${page}&pageSize=50`, session!,
    ),
    enabled: Boolean(session && ledgerId && periodFrom && periodTo),
  })
  const rows = useMemo(() => (query.data?.data || []).flatMap((account) => [
    { ...account, rowType: 'opening' as const, summary: '期初余额' },
    { ...account, rowType: 'period' as const, summary: '本期合计' },
    { ...account, rowType: 'year' as const, summary: '本年累计' },
  ]), [query.data])
  const mergedCell = (row: GeneralLedgerDisplayRow) => ({ rowSpan: row.rowType === 'opening' ? 3 : 0 })
  return <section className="financial-page">
    <BookToolbar title={bookNames['general-ledger']} refreshing={query.isFetching} onRefresh={() => void query.refetch()} />
    {query.isError && <Alert type="error" showIcon message="总账读取失败" />}
    <Card className="financial-grid-card"><Table<GeneralLedgerDisplayRow>
      rowKey={(row) => `${row.accountId}-${row.rowType}`} size="small" className="financial-table"
      loading={query.isLoading || !periodFrom || !periodTo} dataSource={rows}
      locale={{ emptyText: <Empty description="当前期间暂无总账数据" /> }} scroll={{ x: 980 }}
      pagination={false}
      columns={[
        { title: '科目编码', dataIndex: 'accountCode', width: 130, onCell: mergedCell,
          render: (value, row) => row.rowType === 'opening' ? value : null },
        { title: '科目名称', dataIndex: 'accountName', width: 260, onCell: mergedCell,
          render: (value, row) => row.rowType === 'opening' ? value : null },
        { title: '期间', width: 130, render: () => periodFrom === periodTo
          ? periodFrom?.replace('-', '') : `${periodFrom}~${periodTo}` },
        { title: '摘要', dataIndex: 'summary', width: 130 },
        { title: '借方', width: 150, align: 'right', render: (_, row) => row.rowType === 'period' ? money(row.periodDebit) : row.rowType === 'year' ? money(row.yearDebit) : '' },
        { title: '贷方', width: 150, align: 'right', render: (_, row) => row.rowType === 'period' ? money(row.periodCredit) : row.rowType === 'year' ? money(row.yearCredit) : '' },
        { title: '方向', width: 80, align: 'center', render: (_, row) => direction(row.rowType === 'opening' ? row.openingDirection : row.endingDirection) },
        { title: '余额', width: 160, align: 'right', render: (_, row) => money(row.rowType === 'opening' ? row.openingBalance : row.endingBalance) || '0.00' },
      ]} />
      {(query.data?.pagination.totalItems || 0) > 50 && <div className="financial-pagination"><Pagination
        current={page} pageSize={50} total={query.data?.pagination.totalItems || 0} showSizeChanger={false}
        showTotal={(total) => `共 ${total} 个科目`}
        onChange={(nextPage) => { const next = new URLSearchParams(search); next.set('page', String(nextPage)); setSearch(next) }}
      /></div>}
    </Card>
  </section>
}

function accountTree(accounts: Account[]): DataNode[] {
  const children = new Map<string | null, Account[]>()
  accounts.forEach((account) => {
    children.set(account.parentId, [...(children.get(account.parentId) || []), account])
  })
  const build = (parentId: string | null): DataNode[] => (children.get(parentId) || [])
    .sort((left, right) => left.code.localeCompare(right.code))
    .map((account) => ({
      key: account.id,
      title: `${account.code} ${account.name}${account.status === 'INACTIVE' ? '（停用）' : ''}`,
      selectable: true,
      children: build(account.id),
    }))
  return build(null)
}

function filterTree(nodes: DataNode[], keyword: string): DataNode[] {
  if (!keyword.trim()) return nodes
  const normalized = keyword.trim().toLowerCase()
  return nodes.flatMap((node) => {
    const children = filterTree(node.children || [], normalized)
    return String(node.title).toLowerCase().includes(normalized) || children.length
      ? [{ ...node, children }]
      : []
  })
}

function expandedKeysFor(nodes: DataNode[]): string[] {
  return nodes.flatMap((node) => node.children?.length
    ? [String(node.key), ...expandedKeysFor(node.children)]
    : [])
}

function SubLedgerPageView() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const { periodFrom, periodTo } = usePeriodRangeFilter(ledgerId)
  const page = Number(search.get('page') || 1)
  const accountId = search.get('accountId') || undefined
  const keyword = search.get('accountQuery') || ''
  const [expandedKeys, setExpandedKeys] = useState<string[]>([])
  const accounts = useQuery({
    queryKey: ['accounts', ledgerId],
    queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!),
    enabled: Boolean(session && ledgerId),
  })
  const firstAsset = accounts.data?.find((account) =>
    account.status === 'ACTIVE'
      && (account.category === 'CURRENT_ASSET' || account.category === 'NON_CURRENT_ASSET')
      && account.parentId === null,
  )?.id
  useEffect(() => {
    if (accountId || !firstAsset) return
    const next = new URLSearchParams(search)
    next.set('accountId', firstAsset)
    setSearch(next, { replace: true })
  }, [accountId, firstAsset, search, setSearch])
  useEffect(() => setExpandedKeys([]), [ledgerId])
  const query = useQuery({
    queryKey: ['book', ledgerId, 'sub-ledger', periodFrom, periodTo, accountId, page],
    queryFn: () => apiFetch<SubLedgerPage>(
      `/ledgers/${ledgerId}/books/sub-ledger?periodFrom=${periodFrom}&periodTo=${periodTo}&accountId=${accountId}&page=${page}&pageSize=50`, session!,
    ),
    enabled: Boolean(session && ledgerId && periodFrom && periodTo && accountId),
  })
  const tree = useMemo(() => filterTree(accountTree(accounts.data || []), keyword), [accounts.data, keyword])
  const selectedAccount = accounts.data?.find((account) => account.id === accountId)
  const isParentSelection = Boolean(selectedAccount && !selectedAccount.isLeaf)
  const visibleExpandedKeys = keyword.trim() ? expandedKeysFor(tree) : expandedKeys
  const rows = page === 1 && query.data ? [{
    voucherId: 'opening', voucherNumber: '', voucherDate: '', postingAccountId: '', postingAccountCode: '', postingAccountName: '', summary: '期初余额', debit: '', credit: '',
    direction: query.data.openingDirection, balance: query.data.openingBalance,
  }, ...query.data.data] : (query.data?.data || [])
  const updateSearch = (name: string, value?: string) => {
    const next = new URLSearchParams(search)
    if (value) next.set(name, value)
    else next.delete(name)
    if (name !== 'page') next.delete('page')
    setSearch(next)
  }
  return <section className="financial-page">
    <BookToolbar title={bookNames['sub-ledger']} refreshing={query.isFetching} onRefresh={() => void query.refetch()} />
    {query.isError && <Alert type="error" showIcon message="明细账读取失败" />}
    <div className="sub-ledger-layout">
      <Card className="financial-grid-card sub-ledger-table"><Table
        rowKey={(row) => row.voucherId === 'opening' ? 'opening' : `${row.voucherId}-${row.postingAccountId}-${row.summary}`}
        size="small" className="financial-table" loading={query.isLoading || !periodFrom || !periodTo || !accountId}
        dataSource={rows} locale={{ emptyText: <Empty description="当前科目暂无明细" /> }} scroll={{ x: 900 }}
        pagination={false}
        summary={() => query.data ? <Table.Summary.Row>
          <Table.Summary.Cell index={0} colSpan={isParentSelection ? 4 : 3}>本期合计</Table.Summary.Cell>
          <Table.Summary.Cell index={isParentSelection ? 4 : 3} align="right">{money(query.data.periodDebit) || '0.00'}</Table.Summary.Cell>
          <Table.Summary.Cell index={isParentSelection ? 5 : 4} align="right">{money(query.data.periodCredit) || '0.00'}</Table.Summary.Cell>
          <Table.Summary.Cell index={isParentSelection ? 6 : 5} align="center">{direction(query.data.endingDirection)}</Table.Summary.Cell>
          <Table.Summary.Cell index={isParentSelection ? 7 : 6} align="right">{money(query.data.endingBalance) || '0.00'}</Table.Summary.Cell>
        </Table.Summary.Row> : null}
        columns={[
          { title: '日期', dataIndex: 'voucherDate', width: 120 },
          { title: '凭证字号', width: 120, render: (_, row) => row.voucherId === 'opening' ? '' : <Link to={`/ledgers/${ledgerId}/vouchers/${row.voucherId}`}>{row.voucherNumber}</Link> },
          { title: '摘要', dataIndex: 'summary', width: 300 },
          ...(isParentSelection ? [{ title: '明细科目', width: 180, render: (_: unknown, row: SubLedgerEntry) => row.voucherId === 'opening' ? '' : `${row.postingAccountCode} ${row.postingAccountName}` }] : []),
          { title: '借方', dataIndex: 'debit', width: 150, align: 'right', render: money },
          { title: '贷方', dataIndex: 'credit', width: 150, align: 'right', render: money },
          { title: '方向', dataIndex: 'direction', width: 80, align: 'center', render: direction },
          { title: '余额', dataIndex: 'balance', width: 160, align: 'right', render: (value) => money(value) || '0.00' },
        ]} />
        {(query.data?.pagination.totalItems || 0) > 50 && <div className="financial-pagination"><Pagination
          current={page} pageSize={50} total={query.data?.pagination.totalItems || 0} showSizeChanger={false}
          showTotal={(total) => `共 ${total} 条明细`} onChange={(nextPage) => updateSearch('page', String(nextPage))}
        /></div>}
      </Card>
      <aside className="account-switcher" aria-label="科目快速切换">
        <div className="account-switcher-title">快速切换</div>
        <Input.Search aria-label="搜索科目" allowClear placeholder="科目编码或名称" value={keyword}
          onChange={(event) => updateSearch('accountQuery', event.target.value)} />
        <Tree treeData={tree} selectedKeys={accountId ? [accountId] : []} expandedKeys={visibleExpandedKeys}
          onExpand={(keys) => setExpandedKeys(keys.map(String))}
          onSelect={(keys) => keys[0] && updateSearch('accountId', String(keys[0]))} />
      </aside>
    </div>
  </section>
}
