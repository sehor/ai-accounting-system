import { Alert, Button, Card, Empty, Space, Spin, Switch, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useNavigate, useParams } from 'react-router-dom'
import { useState } from 'react'
import { apiHeaders, apiResponse, openApiClient, ApiError } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { PeriodRangeSelector, PeriodSelector, usePeriodFilter, usePeriodRangeFilter } from '../components/PeriodSelector'
import { CashFlowChecksAlert, CashFlowQualityAlert, CashFlowStatementTable, formatReportAmount } from '../components/CashFlowStatement'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'

export { formatReportAmount }

const reportNames: Record<string, string> = {
  'trial-balance': '科目余额表',
  'balance-sheet': '资产负债表',
  'income-statement': '利润表',
  'cash-flow': '现金流量表',
}

type Statement = components['schemas']['AccountStatement']
type StatutoryLine = components['schemas']['StatutoryStatementLine']
type StatutoryStatement = components['schemas']['StatutoryStatement']
type TrialBalanceLine = components['schemas']['TrialBalanceLine']

/** Friendlier cash-flow error hints keyed by backend problem code. */
const cashFlowErrorHints: Record<string, string> = {
  STATUTORY_REPORT_UNSUPPORTED_STANDARD: '当前账套不是小企业会计准则，暂不提供法定报表',
  STATUTORY_REPORT_CURRENCY_UNSUPPORTED: '小企业会计准则法定报表首版仅支持人民币账套',
  STATUTORY_REPORT_PROJECTION_PENDING: '余额投影正在更新，请稍后刷新报表',
  STATUTORY_FORMULA_NOT_FOUND: '当前账套缺少已发布的报表公式',
  PERIOD_NOT_FOUND: '所选年度没有可用会计期间',
}

export function reportRowKey(row: TrialBalanceLine | { code: string } | { voucherId: string }, index = 0): string {
  if ('accountId' in row) return row.accountId
  if ('voucherId' in row) return `${row.voucherId}-${index}`
  return `${row.code}-${index}`
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

function CashFlowStatementView({ statement, ledgerId }: { statement: StatutoryStatement; ledgerId: string }) {
  return <Space direction="vertical" size={12} style={{ width: '100%' }}>
    <CashFlowChecksAlert checks={statement.checks} />
    <CashFlowQualityAlert dataQuality={statement.dataQuality} ledgerId={ledgerId} />
    <Card className="financial-grid-card">
      <CashFlowStatementTable statement={statement} />
    </Card>
  </Space>
}

export function ReportsPage() {
  const { ledgerId = '', reportType = 'balance-sheet' } = useParams()
  const { session } = useAuth()
  const navigate = useNavigate()
  const [search, setSearch] = useWorkspaceSearchParams()
  const includeParents = search.get('includeParents') === 'true'
  const [balanceSource, setBalanceSource] = useState<string | null>(null)
  const cashFlow = reportType === 'cash-flow'
  const statement = reportType === 'balance-sheet' || reportType === 'income-statement' || cashFlow
  const ledger = useQuery({
    queryKey: ['ledger-profile', ledgerId],
    queryFn: async () => (await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}', { params: { path: { ledgerId } }, headers: apiHeaders(session!) }))).data,
    enabled: Boolean(session && ledgerId),
  })
  // The cash-flow report has no legacy fallback: always call the statutory endpoint
  // and surface the backend's standard/currency errors for unsupported ledgers.
  const statutory = ledger.isSuccess && statement && (ledger.data?.accountingStandardCode?.toUpperCase() === 'SME' || cashFlow)
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
  const problemCode = query.error instanceof ApiError ? query.error.problem.code : undefined
  const queryError = query.error instanceof ApiError ? query.error.message : undefined
  const cashFlowHint = cashFlow && problemCode ? cashFlowErrorHints[problemCode] : undefined
  const profileError = ledger.error instanceof ApiError ? ledger.error.message : undefined
  const statutoryData = statutory ? query.data as StatutoryStatement | undefined : undefined
  const statutoryPeriodsError = singlePeriods.error instanceof ApiError ? singlePeriods.error.message : undefined
  const noStatutoryPeriods = statutory && singlePeriods.isSuccess && (singlePeriods.data?.length || 0) === 0
  const statutoryLoading = statutory && (singlePeriods.isLoading || query.isLoading)

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
        {cashFlow && statutoryData && <Button onClick={() => navigate(`/ledgers/${ledgerId}/settings/report-formulas?formula=CASH_FLOW`)}>调整公式</Button>}
      </Space>
    </div>
    {profileError && <Alert type="error" showIcon message="账套信息读取失败" description={profileError} />}
    {statutory && singlePeriods.isError && <Alert type="error" showIcon message="会计期间读取失败" description={statutoryPeriodsError || '请稍后重试。'} />}
    {noStatutoryPeriods && <Alert
      type="warning"
      showIcon
      message="尚未设置会计期间"
      description="请先到账套设置中创建会计期间，再生成法定报表。"
      action={<Button size="small" onClick={() => navigate(`/ledgers/${ledgerId}/settings/periods`)}>设置会计期间</Button>}
    />}
    {query.isError && <Alert type="error" showIcon message={cashFlowHint || '报表读取失败'} description={queryError || cashFlowHint || '请检查期间、币种或权限后重试。'} />}
    {statutory
      ? statutoryData
        ? cashFlow
          ? <CashFlowStatementView statement={statutoryData} ledgerId={ledgerId} />
          : <StatutoryStatementView statement={statutoryData} />
        : null
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
    {statutoryLoading && <div className="statutory-loading" role="status" aria-label="正在生成法定报表"><Spin /><Typography.Text>正在生成法定报表…</Typography.Text></div>}
  </section>
}
