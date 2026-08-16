import { Alert, Card, Empty, Space, Spin, Switch, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { useState } from 'react'
import { apiHeaders, apiResponse, openApiClient, ApiError } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { PeriodRangeSelector, PeriodSelector, usePeriodFilter, usePeriodRangeFilter } from '../components/PeriodSelector'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

const reportNames: Record<string, string> = {
  'trial-balance': '科目余额表',
  'balance-sheet': '资产负债表',
  'income-statement': '利润表',
}

type Statement = components['schemas']['AccountStatement']
type StatutoryLine = components['schemas']['StatutoryStatementLine']
type StatutoryStatement = components['schemas']['StatutoryStatement']
type TrialBalanceLine = components['schemas']['TrialBalanceLine']

export function reportRowKey(row: TrialBalanceLine | { code: string } | { voucherId: string }, index = 0): string {
  if ('accountId' in row) return row.accountId
  if ('voucherId' in row) return `${row.voucherId}-${index}`
  return `${row.code}-${index}`
}

export function formatReportAmount(value: string | number | null | undefined): string {
  const amount = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(amount) || amount === 0) return ''
  return new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
}

function statutoryRowClass(row: StatutoryLine): string {
  return `statutory-row statutory-row-${row.rowType.toLowerCase().replace(/[^a-z0-9_-]/g, '-')}`
}

function StatutoryTable({ statement, group }: {
  statement: StatutoryStatement
  group: StatutoryStatement['groups'][number]
}) {
  return <table className="statutory-statement-table">
    <thead><tr>
      <th>项目</th><th className="statutory-line-no">行次</th>
      <th className="statutory-amount">{statement.primaryColumn}</th>
      <th className="statutory-amount">{statement.comparativeColumn}</th>
    </tr></thead>
    <tbody>
      {group.lines.map((row) => <tr key={row.key} className={statutoryRowClass(row)}>
        <td className="statutory-name" style={{ paddingLeft: `${10 + row.indent * 24}px` }}>{row.name}</td>
        <td className="statutory-line-no">{row.lineNo || ''}</td>
        <td className="statutory-amount">{formatReportAmount(row.primaryAmount)}</td>
        <td className="statutory-amount">{formatReportAmount(row.comparativeAmount)}</td>
      </tr>)}
    </tbody>
  </table>
}

function StatutoryStatementView({ statement }: { statement: StatutoryStatement }) {
  const balance = statement.reportType === 'balance-sheet'
  const failedChecks = statement.checks.filter((check) => !check.passed)
  const tables = <>{statement.groups.map((group) => <Card key={group.key} className="financial-grid-card statutory-card" title={group.title}>
    <div className="statutory-table-scroll"><StatutoryTable statement={statement} group={group} /></div>
  </Card>)}</>
  if (balance) return <>
    {failedChecks.length > 0 && <Alert
      className="statutory-balance-warning"
      type="warning"
      showIcon
      message="资产负债表勾稽不平"
      description={failedChecks.map((check) => `${check.name}，差额 ${formatReportAmount(check.difference) || '0.00'}`).join('；')}
    />}
    <div className="statutory-balance-scroll"><div className="statutory-balance-layout">{tables}</div></div>
  </>
  return <div className="statutory-income-layout">{tables}</div>
}

export function ReportsPage() {
  const { ledgerId = '', reportType = 'balance-sheet' } = useParams()
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const includeParents = search.get('includeParents') === 'true'
  const [balanceSource, setBalanceSource] = useState<string | null>(null)
  const statement = reportType === 'balance-sheet' || reportType === 'income-statement'
  const ledger = useQuery({
    queryKey: ['ledger-profile', ledgerId],
    queryFn: async () => (await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}', { params: { path: { ledgerId } }, headers: apiHeaders(session!) }))).data,
    enabled: Boolean(session && ledgerId),
  })
  const statutory = ledger.isSuccess && statement && ledger.data?.accountingStandardCode?.toUpperCase() === 'SME'
  const legacyReport = ledger.isSuccess && !statutory
  const { periods: rangePeriods, periodFrom, periodTo, setPeriodRange } = usePeriodRangeFilter(ledgerId, legacyReport)
  const { periods: singlePeriods, periodCode, setPeriodCode } = usePeriodFilter(ledgerId, statutory)
  const query = useQuery({
    queryKey: ['report', ledgerId, reportType, statutory, periodCode, periodFrom, periodTo, includeParents],
    queryFn: async () => {
      const options = { headers: apiHeaders(session!) }
      const result = statutory
        ? await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}/reports/statutory/{reportType}', {
            ...options, params: { path: { ledgerId, reportType }, query: { periodCode: periodCode! } },
          }))
        : reportType === 'trial-balance'
          ? await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}/reports/trial-balance', {
              ...options, params: { path: { ledgerId }, query: { periodFrom: periodFrom!, periodTo: periodTo!, includeParents } },
            }))
          : reportType === 'balance-sheet'
            ? await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}/reports/balance-sheet', {
                ...options, params: { path: { ledgerId }, query: { periodFrom: periodFrom!, periodTo: periodTo! } },
              }))
            : await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}/reports/income-statement', {
                ...options, params: { path: { ledgerId }, query: { periodFrom: periodFrom!, periodTo: periodTo! } },
              }))
      setBalanceSource(result.response.headers.get('X-Balance-Source'))
      return result.data
    },
    enabled: Boolean(session && ledgerId && reportNames[reportType] && ledger.isSuccess
      && (statutory ? periodCode : periodFrom && periodTo)),
  })
  const rows = !statutory && statement ? ((query.data as Statement | undefined)?.lines || []) : (!statutory ? (query.data || []) : [])
  const columns = (statement
    ? [
        { title: '编码', dataIndex: 'code', width: 120 },
        { title: '项目', dataIndex: 'name', width: 360 },
        { title: '金额', dataIndex: 'amount', width: 180, align: 'right' as const },
      ]
    : [
        { title: '科目编码', dataIndex: 'code', width: 140 },
        { title: '科目名称', dataIndex: 'name', width: 320 },
        { title: '期初借', dataIndex: 'openingDebit', width: 140, align: 'right' as const },
        { title: '期初贷', dataIndex: 'openingCredit', width: 140, align: 'right' as const },
        { title: '发生借', dataIndex: 'periodDebit', width: 140, align: 'right' as const },
        { title: '发生贷', dataIndex: 'periodCredit', width: 140, align: 'right' as const },
        { title: '期末借', dataIndex: 'closingDebit', width: 140, align: 'right' as const },
        { title: '期末贷', dataIndex: 'closingCredit', width: 140, align: 'right' as const },
      ]) as TableProps<TrialBalanceLine>['columns']

  const toggleParents = (checked: boolean) => {
    const next = new URLSearchParams(search)
    if (checked) next.set('includeParents', 'true')
    else next.delete('includeParents')
    setSearch(next)
  }
  const queryError = query.error instanceof ApiError ? query.error.problem.detail : undefined
  const profileError = ledger.error instanceof ApiError ? ledger.error.problem.detail : undefined
  const statutoryData = statutory ? query.data as StatutoryStatement | undefined : undefined

  return <section className="financial-page" aria-labelledby="report-title">
    <div className="financial-toolbar">
      <div>
        <Typography.Title id="report-title" level={1}>{reportNames[reportType] || '报表'}</Typography.Title>
        {statutory && <Typography.Text type="secondary">小企业会计准则 · {ledger.data?.baseCurrency || 'CNY'}</Typography.Text>}
      {statutoryData?.formulaVersion && <Tag color="green">公式版本 v{statutoryData.formulaVersion}</Tag>}
      {(query.data as Statement | undefined)?.formulaVersion && !statutoryData
        && <Tag color="green">公式版本 v{(query.data as Statement).formulaVersion}</Tag>}
      </div>
      {balanceSource && <Tag color={balanceSource === 'projection' ? 'blue' : 'orange'}>
        数据来源：{balanceSource === 'projection' ? '余额投影' : '实时凭证'}
      </Tag>}
      <Space wrap className="financial-toolbar-actions">
        {!statutory && reportType === 'trial-balance' && <Switch
          checked={includeParents}
          checkedChildren="含父级"
          unCheckedChildren="仅末级"
          onChange={toggleParents}
        />}
        {statutory
          ? <PeriodSelector
              periodCode={periodCode}
              periods={singlePeriods.data || []}
              loading={singlePeriods.isLoading}
              refreshing={query.isFetching}
              onChange={setPeriodCode}
              onRefresh={() => void query.refetch()}
            />
          : <PeriodRangeSelector
              periodFrom={periodFrom}
              periodTo={periodTo}
              periods={rangePeriods.data || []}
              loading={rangePeriods.isLoading}
              refreshing={query.isFetching}
              onChange={setPeriodRange}
              onRefresh={() => void query.refetch()}
            />}
      </Space>
    </div>
    {profileError && <Alert type="error" showIcon message="账套信息读取失败" description={profileError} />}
    {query.isError && <Alert type="error" showIcon message="报表读取失败" description={queryError || '请检查期间、币种或权限后重试。'} />}
    {statutoryData
      ? <StatutoryStatementView statement={statutoryData} />
      : <Card className="financial-grid-card">
          <Table
            rowKey={reportRowKey}
            size="small"
            className="financial-table"
            loading={query.isLoading || !periodFrom || !periodTo || !ledger.isSuccess}
            dataSource={rows as TrialBalanceLine[]}
            locale={{ emptyText: <Empty description="当前期间暂无报表数据" /> }}
            columns={columns}
            pagination={false}
            scroll={{ x: 1260 }}
          />
        </Card>}
    {statutory && query.isLoading && <div className="statutory-loading"><Spin tip="正在生成法定报表…" /></div>}
  </section>
}
