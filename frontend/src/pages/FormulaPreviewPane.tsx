import { Alert, Card, Checkbox, Empty, Space, Spin, Table, Typography } from 'antd'
import type { components } from '../api/generated'
import { formatReportAmount } from './ReportsPage'
import { CashFlowChecksAlert, CashFlowQualityAlert } from '../components/CashFlowStatement'
import type { FormulaDefinition } from '../features/reportFormulas/types'

type PreviewResult = components['schemas']['ReportFormulaPreviewResult']

interface Props {
  result: PreviewResult | undefined
  loading: boolean
  definition: FormulaDefinition | undefined
  canPublish: boolean
  publishing: boolean
  acknowledged: boolean
  onAcknowledge: (checked: boolean) => void
  onPublish: () => void
  ledgerId?: string
}

type PreviewStatement =
  | {
      reportType?: string
      groups?: { key: string; title: string; lines: { key: string; lineNo: number; name: string; indent: number; rowType?: string; primaryAmount: string | number; comparativeAmount?: string | number }[] }[]
      checks?: { key: string; name: string; passed: boolean; difference: string | number }[]
      primaryColumn?: string
      comparativeColumn?: string
      dataQuality?: { status?: string; primaryUnclassifiedVoucherCount?: number; primaryUnclassifiedLineCount?: number; comparativeUnclassifiedVoucherCount?: number; comparativeUnclassifiedLineCount?: number; samples?: { voucherId: string; voucherNumber: string; periodCode: string; voucherDate: string; lineNo: number; side: string; baseAmount: string; reason: string }[] }
    }
  | { totalLines?: number; lines?: { code: string; name: string; amount: string | number }[] }
  | undefined

export function FormulaPreviewPane({ result, loading, definition, canPublish, publishing, acknowledged, onAcknowledge, onPublish, ledgerId }: Props) {
  const issues = result?.blockingIssues || []
  const warnings = result?.warnings || []
  const statement = result?.statement as PreviewStatement | undefined
  const statutory = statement && 'groups' in statement ? statement : undefined
  const cashFlow = statutory?.reportType === 'cash-flow' || Boolean(statutory?.dataQuality)
  const hasPreviewWarnings = warnings.length > 0 || (cashFlow && statutory?.dataQuality?.status === 'INCOMPLETE')
  return <Card
    title="试算结果"
    extra={<Space>
      {hasPreviewWarnings && <Checkbox checked={acknowledged} onChange={(event) => onAcknowledge(event.target.checked)}>确认发布带警告的版本</Checkbox>}
      <button className="ant-btn ant-btn-primary" disabled={!canPublish} onClick={onPublish} aria-label="发布草稿">
        {publishing ? '发布中…' : '发布'}
      </button>
    </Space>}
  >
    <Spin spinning={loading}>
      {issues.length > 0 && <Alert type="error" showIcon message="存在阻断问题，请修正后重新试算" description={<ul style={{ margin: 0, paddingLeft: 20 }}>{issues.map((issue, index) => <li key={index}>{issue.path || '定义'}: {issue.message}</li>)}</ul>} />}
      {warnings.length > 0 && <Alert type="warning" showIcon style={{ marginTop: 12 }} message="勾稽不平" description={warnings.map((warning) => `${warning.name}，差额 ${formatReportAmount(warning.difference) || '0.00'}`).join('；')} />}
      {!issues.length && !statement && !loading && <Empty description="尚未试算" />}
      {cashFlow && statutory && <Space direction="vertical" size={12} style={{ width: '100%', marginTop: 12 }}>
        <CashFlowChecksAlert checks={(statutory.checks || []) as unknown as components['schemas']['Check'][]} />
        {statutory.dataQuality && <CashFlowQualityAlert dataQuality={statutory.dataQuality as unknown as components['schemas']['DataQuality']} ledgerId={ledgerId || ''} />}
        <PreviewStatutory statement={statutory} continuous />
      </Space>}
      {statutory && !cashFlow && <PreviewStatutory statement={statutory} />}
      {statement && 'lines' in statement && <Table
        rowKey="code"
        size="small"
        dataSource={statement.lines || []}
        pagination={false}
        columns={[
          { title: '编码', dataIndex: 'code', width: 120 },
          { title: '项目', dataIndex: 'name' },
          { title: '金额', dataIndex: 'amount', width: 160, align: 'right', render: (value: string | number) => formatReportAmount(value) },
        ]}
      />}
      {definition && definition.kind === 'FIXED_LINES' && !result && !loading && <Typography.Text type="secondary">试算通过后此处显示报表预览。</Typography.Text>}
    </Spin>
  </Card>
}

function PreviewStatutory({ statement, continuous = false }: {
  statement: { groups?: { key: string; title: string; lines: { key: string; lineNo: number; name: string; indent: number; rowType?: string; primaryAmount: string | number; comparativeAmount?: string | number }[] }[]; primaryColumn?: string; comparativeColumn?: string }
  continuous?: boolean
}) {
  if (continuous) {
    return <div className="statutory-table-scroll cash-flow-scroll">
      <table className="statutory-statement-table cash-flow-statement-table">
        <thead><tr><th>项目</th><th className="statutory-line-no">行次</th><th className="statutory-amount">{statement.primaryColumn}</th><th className="statutory-amount">{statement.comparativeColumn}</th></tr></thead>
        <tbody>
          {statement.groups?.map((group) => <>
            <tr key={`${group.key}-title`} className="statutory-row statutory-row-section">
              <td className="statutory-name">{group.title}</td>
              <td className="statutory-line-no" />
              <td className="statutory-amount" />
              <td className="statutory-amount" />
            </tr>
            {group.lines.map((line) => <tr key={line.key} className={`statutory-row ${line.rowType === 'TOTAL' || line.rowType === 'CALCULATION' ? 'statutory-row-total' : ''}`}>
              <td className="statutory-name" style={{ paddingLeft: `${10 + line.indent * 24}px` }}>{line.name}</td>
              <td className="statutory-line-no">{line.lineNo || ''}</td>
              <td className="statutory-amount">{formatReportAmount(line.primaryAmount)}</td>
              <td className="statutory-amount">{formatReportAmount(line.comparativeAmount)}</td>
            </tr>)}
          </>)}
        </tbody>
      </table>
    </div>
  }
  return <Space direction="vertical" style={{ width: '100%' }}>
    {statement.groups?.map((group) => <Card key={group.key} size="small" title={group.title}>
      <div style={{ overflowX: 'auto' }}>
        <table className="statutory-statement-table">
          <thead><tr><th>项目</th><th>行次</th><th>{statement.primaryColumn}</th><th>{statement.comparativeColumn}</th></tr></thead>
          <tbody>
            {group.lines.map((line) => <tr key={line.key}>
              <td style={{ paddingLeft: `${10 + line.indent * 24}px` }}>{line.name}</td>
              <td>{line.lineNo || ''}</td>
              <td style={{ textAlign: 'right' }}>{formatReportAmount(line.primaryAmount)}</td>
              <td style={{ textAlign: 'right' }}>{formatReportAmount(line.comparativeAmount)}</td>
            </tr>)}
          </tbody>
        </table>
      </div>
    </Card>)}
  </Space>
}
