import { Alert, Collapse, Space, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { Fragment } from 'react'
import type { components } from '../api/generated'

type StatutoryStatement = components['schemas']['StatutoryStatement']
type StatutoryLine = components['schemas']['StatutoryStatementLine']
type DataQuality = components['schemas']['DataQuality']
type QualitySample = components['schemas']['QualitySample']

/** Formats a financial amount: thousands separator, two decimals, zero blank, negative sign kept. */
export function formatReportAmount(value: string | number | null | undefined): string {
  const amount = typeof value === 'number' ? value : Number(value)
  if (!Number.isFinite(amount) || amount === 0) return ''
  return new Intl.NumberFormat('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 }).format(amount)
}

export const cashFlowSampleReasons: Record<string, string> = {
  ITEM_MISSING: '未填写现金流项目',
  LEGACY_COARSE_ITEM: '使用旧的三分类项目',
  ITEM_NOT_IN_FORMULA: '项目未被当前报表公式引用',
  ITEM_INACTIVE: '项目已停用',
}

export function cashFlowSampleReason(reason: string): string {
  return cashFlowSampleReasons[reason] || reason
}

function statutoryRowClass(row: StatutoryLine): string {
  return `statutory-row statutory-row-${row.rowType.toLowerCase().replace(/[^a-z0-9_-]/g, '-')}`
}

/**
 * Single continuous statutory statement table. API groups render as grey in-table
 * section rows instead of separate cards; totals stay bold with a stronger border.
 */
export function CashFlowStatementTable({ statement }: { statement: StatutoryStatement }) {
  return <div className="statutory-table-scroll cash-flow-scroll">
    <table className="statutory-statement-table cash-flow-statement-table">
      <thead><tr>
        <th scope="col">项目</th>
        <th scope="col" className="statutory-line-no">行次</th>
        <th scope="col" className="statutory-amount">{statement.primaryColumn}</th>
        <th scope="col" className="statutory-amount">{statement.comparativeColumn}</th>
      </tr></thead>
      <tbody>
        {statement.groups.map((group) => <Fragment key={group.key}>
          <tr className="statutory-row statutory-row-section">
            <td className="statutory-name">{group.title}</td>
            <td className="statutory-line-no" />
            <td className="statutory-amount" />
            <td className="statutory-amount" />
          </tr>
          {group.lines.map((row) => <tr key={row.key} className={statutoryRowClass(row)}>
            <td className="statutory-name" style={{ paddingLeft: `${10 + row.indent * 24}px` }}>{row.name}</td>
            <td className="statutory-line-no">{row.lineNo || ''}</td>
            <td className="statutory-amount">{formatReportAmount(row.primaryAmount)}</td>
            <td className="statutory-amount">{formatReportAmount(row.comparativeAmount)}</td>
          </tr>)}
        </Fragment>)}
      </tbody>
    </table>
  </div>
}

function SampleTable({ samples, ledgerId }: { samples: QualitySample[]; ledgerId: string }) {
  const columns: TableProps<QualitySample>['columns'] = [
    { title: '凭证号', width: 120, render: (_, sample) => <Typography.Link href={`/ledgers/${ledgerId}/vouchers/${sample.voucherId}`}>{sample.voucherNumber}</Typography.Link> },
    { title: '日期', dataIndex: 'voucherDate', width: 110 },
    { title: '期间', dataIndex: 'periodCode', width: 90 },
    { title: '分录行', dataIndex: 'lineNo', width: 70, align: 'right' },
    { title: '方向', dataIndex: 'side', width: 70, render: (side: string) => <Tag>{side === 'DEBIT' ? '借' : side === 'CREDIT' ? '贷' : side}</Tag> },
    { title: '金额', dataIndex: 'baseAmount', width: 120, align: 'right', render: (value: string) => formatReportAmount(value) },
    { title: '原因', dataIndex: 'reason', render: (reason: string) => cashFlowSampleReason(reason) },
  ]
  return <Table<QualitySample>
    rowKey={(sample, index = 0) => `${sample.voucherId}-${sample.lineNo}-${index}`}
    size="small"
    dataSource={samples}
    columns={columns}
    pagination={false}
    scroll={{ x: 720 }}
  />
}

/**
 * Data completeness alert: a short success status when complete, otherwise a
 * warning listing per-column unclassified counts plus an expandable sample list.
 */
export function CashFlowQualityAlert({ dataQuality, ledgerId }: { dataQuality: DataQuality; ledgerId: string }) {
  if (dataQuality.status === 'COMPLETE') {
    return <Alert className="cash-flow-quality" type="success" showIcon message="数据完整" />
  }
  const samples = dataQuality.samples || []
  return <Alert
    className="cash-flow-quality"
    type="warning"
    showIcon
    message="存在未分类的现金收支"
    description={<Space direction="vertical" size={8} style={{ width: '100%' }}>
      <Typography.Text>
        未分类的现金收支金额未计入下列现金流项目行。本年累计：
        {dataQuality.primaryUnclassifiedVoucherCount} 张凭证 / {dataQuality.primaryUnclassifiedLineCount} 行；
        本月：{dataQuality.comparativeUnclassifiedVoucherCount} 张凭证 / {dataQuality.comparativeUnclassifiedLineCount} 行。
      </Typography.Text>
      {samples.length > 0 && <Collapse
        ghost
        items={[{
          key: 'samples',
          label: `查看定位样例（最多 ${samples.length} 条）`,
          children: <SampleTable samples={samples} ledgerId={ledgerId} />,
        }]}
      />}
    </Space>}
  />
}

/** Failed statutory reconciliation checks, kept separate from data completeness. */
export function CashFlowChecksAlert({ checks }: { checks: StatutoryStatement['checks'] }) {
  const failed = checks.filter((check) => !check.passed)
  if (failed.length === 0) return null
  return <Alert
    className="cash-flow-checks"
    type="error"
    showIcon
    message="勾稽检查未通过"
    description={failed.map((check) => `${check.name}，差额 ${formatReportAmount(check.difference) || '0.00'}`).join('；')}
  />
}
