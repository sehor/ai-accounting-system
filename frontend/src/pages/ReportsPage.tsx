import { Alert, Card, DatePicker, Empty, Select, Space, Switch, Table, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useQuery } from '@tanstack/react-query'
import dayjs from 'dayjs'
import { useSearchParams, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { LedgerLine, Statement, TrialBalanceLine } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

const reportNames: Record<string, string> = { 'trial-balance': '科目余额表', 'balance-sheet': '资产负债表', 'income-statement': '利润表', 'general-ledger': '总账', 'sub-ledger': '明细账' }

export function reportRowKey(row: TrialBalanceLine | LedgerLine | { code: string }, index = 0): string {
  if ('voucherId' in row) return `${row.voucherId}-${index}`
  if ('accountId' in row) return row.accountId
  return `${row.code}-${index}`
}

export function ReportsPage() {
  const { ledgerId = '', reportType = 'trial-balance' } = useParams(); const { session } = useAuth(); const [search, setSearch] = useSearchParams(); const periodCode = search.get('periodCode') || undefined; const includeParents = search.get('includeParents') === 'true'
  const reportParams = new URLSearchParams(); if (periodCode) reportParams.set('periodCode', periodCode); if (reportType === 'trial-balance' && includeParents) reportParams.set('includeParents', 'true')
  const query = useQuery({ queryKey: ['report', ledgerId, reportType, periodCode, includeParents], queryFn: () => apiFetch<TrialBalanceLine[] | Statement | LedgerLine[]>(`/ledgers/${ledgerId}/reports/${reportType}${reportParams.size ? `?${reportParams}` : ''}`, session!), enabled: Boolean(session && ledgerId && reportType) })
  const data = query.data
  const columns = (reportType === 'trial-balance' ? [{ title: '科目编码', dataIndex: 'code' }, { title: '科目名称', dataIndex: 'name' }, { title: '借方', dataIndex: 'debit' }, { title: '贷方', dataIndex: 'credit' }, { title: '余额', dataIndex: 'balance' }] : reportType === 'balance-sheet' || reportType === 'income-statement' ? [{ title: '编码', dataIndex: 'code' }, { title: '项目', dataIndex: 'name' }, { title: '金额', dataIndex: 'amount' }] : [{ title: '凭证号', dataIndex: 'voucherNumber' }, { title: '日期', dataIndex: 'voucherDate' }, { title: '科目', render: (_: unknown, row: LedgerLine) => `${row.accountCode} ${row.accountName}` }, { title: '方向', dataIndex: 'side' }, { title: '金额', dataIndex: 'amount' }]) as unknown as TableProps<TrialBalanceLine | LedgerLine>['columns']
  const rows = reportType === 'balance-sheet' || reportType === 'income-statement' ? ((data as Statement | undefined)?.lines || []) : (data || [])
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>{reportNames[reportType] || '报表'}</Typography.Title><Typography.Text type="secondary">固定报表指标和期间筛选。</Typography.Text></div><Space>{reportType === 'trial-balance' && <Switch checked={includeParents} checkedChildren="含父级" unCheckedChildren="仅末级" onChange={(checked) => { const next = new URLSearchParams(search); if (checked) next.set('includeParents', 'true'); else next.delete('includeParents'); setSearch(next) }} />}<Select value={reportType} style={{ width: 160 }} options={Object.entries(reportNames).map(([value, label]) => ({ value, label }))} onChange={(value) => { window.location.href = `/ledgers/${ledgerId}/reports/${value}` }} /><DatePicker picker="month" value={periodCode ? dayjs(`${periodCode}-01`) : undefined} onChange={(value) => { const next = new URLSearchParams(search); if (value) next.set('periodCode', value.format('YYYY-MM')); else next.delete('periodCode'); setSearch(next) }} /></Space></div>
    {query.isError && <Alert type="error" message="报表读取失败" description="请检查期间或权限后重试。" />}
    <Card><Table<TrialBalanceLine | LedgerLine> rowKey={reportRowKey} loading={query.isLoading} dataSource={rows as unknown as (TrialBalanceLine | LedgerLine)[]} locale={{ emptyText: <Empty description="暂无报表数据" /> }} columns={columns} scroll={{ x: 720 }} /></Card>
  </Space>
}
