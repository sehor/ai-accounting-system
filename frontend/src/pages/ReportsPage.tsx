import { Alert, Card, Empty, Space, Switch, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { useState } from 'react'
import { apiFetchWithHeaders } from '../api/client'
import type { Statement, TrialBalanceLine } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { PeriodSelector, usePeriodFilter } from '../components/PeriodSelector'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

const reportNames: Record<string, string> = {
  'trial-balance': '科目余额表',
  'balance-sheet': '资产负债表',
  'income-statement': '利润表',
}

export function reportRowKey(row: TrialBalanceLine | { code: string }, index = 0): string {
  if ('accountId' in row) return row.accountId
  return `${row.code}-${index}`
}

export function ReportsPage() {
  const { ledgerId = '', reportType = 'balance-sheet' } = useParams()
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const { periods, periodCode, setPeriodCode } = usePeriodFilter(ledgerId)
  const includeParents = search.get('includeParents') === 'true'
  const [balanceSource, setBalanceSource] = useState<string | null>(null)
  const reportParams = new URLSearchParams()
  if (periodCode) reportParams.set('periodCode', periodCode)
  if (reportType === 'trial-balance' && includeParents) reportParams.set('includeParents', 'true')
  const query = useQuery({
    queryKey: ['report', ledgerId, reportType, periodCode, includeParents],
    queryFn: async () => {
      const response = await apiFetchWithHeaders<TrialBalanceLine[] | Statement>(
        `/ledgers/${ledgerId}/reports/${reportType}?${reportParams}`, session!,
      )
      setBalanceSource(response.headers.get('X-Balance-Source'))
      return response.data
    },
    enabled: Boolean(session && ledgerId && periodCode && reportNames[reportType]),
  })
  const statement = reportType === 'balance-sheet' || reportType === 'income-statement'
  const rows = statement ? ((query.data as Statement | undefined)?.lines || []) : (query.data || [])
  const columns = (statement
    ? [
        { title: '编码', dataIndex: 'code', width: 120 },
        { title: '项目', dataIndex: 'name', width: 360 },
        { title: '金额', dataIndex: 'amount', width: 180, align: 'right' as const },
      ]
    : [
        { title: '科目编码', dataIndex: 'code', width: 140 },
        { title: '科目名称', dataIndex: 'name', width: 320 },
        { title: '借方', dataIndex: 'debit', width: 160, align: 'right' as const },
        { title: '贷方', dataIndex: 'credit', width: 160, align: 'right' as const },
        { title: '余额', dataIndex: 'balance', width: 160, align: 'right' as const },
      ]) as TableProps<TrialBalanceLine>['columns']

  const toggleParents = (checked: boolean) => {
    const next = new URLSearchParams(search)
    if (checked) next.set('includeParents', 'true')
    else next.delete('includeParents')
    setSearch(next)
  }

  return <section className="financial-page" aria-labelledby="report-title">
    <div className="financial-toolbar">
      <Typography.Title id="report-title" level={1}>{reportNames[reportType] || '报表'}</Typography.Title>
      {balanceSource && <Tag color={balanceSource === 'projection' ? 'blue' : 'orange'}>
        数据来源：{balanceSource === 'projection' ? '余额投影' : '实时凭证'}
      </Tag>}
      <Space wrap>
        {reportType === 'trial-balance' && <Switch
          checked={includeParents}
          checkedChildren="含父级"
          unCheckedChildren="仅末级"
          onChange={toggleParents}
        />}
        <PeriodSelector
          periodCode={periodCode}
          periods={periods.data || []}
          loading={periods.isLoading}
          refreshing={query.isFetching}
          onChange={setPeriodCode}
          onRefresh={() => void query.refetch()}
        />
      </Space>
    </div>
    {query.isError && <Alert type="error" showIcon message="报表读取失败" description="请检查期间或权限后重试。" />}
    <Card className="financial-grid-card">
      <Table
        rowKey={reportRowKey}
        size="small"
        className="financial-table"
        loading={query.isLoading || !periodCode}
        dataSource={rows as TrialBalanceLine[]}
        locale={{ emptyText: <Empty description="当前期间暂无报表数据" /> }}
        columns={columns}
        pagination={false}
        scroll={{ x: 780 }}
      />
    </Card>
  </section>
}
