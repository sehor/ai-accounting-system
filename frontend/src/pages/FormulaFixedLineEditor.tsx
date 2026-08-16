import { Input, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { FormulaExpressionEditor } from './FormulaExpressionEditor'
import { allLines, expressionToJson } from '../features/reportFormulas/types'
import type { FormulaDefinition, FormulaLine, Account } from '../features/reportFormulas/types'
import type { components } from '../api/generated'

type CashFlowItem = components['schemas']['LedgerCashFlowItem']

interface Props {
  definition: FormulaDefinition
  onLineChange: (lineKey: string, patch: { name?: string; expression?: FormulaLine['expression'] }) => void
  accounts: Account[]
  cashFlowItems?: CashFlowItem[]
  balanceSheet: boolean
  cashFlow?: boolean
  readOnly: boolean
}

export function FormulaFixedLineEditor({ definition, onLineChange, accounts, cashFlowItems = [], balanceSheet, cashFlow = false, readOnly }: Props) {
  const previous = (lineKey: string) => allLines(definition)
    .filter((line) => line.key !== lineKey)
    .slice(0, allLines(definition).findIndex((line) => line.key === lineKey))
    .map((line) => ({ key: line.key, name: line.name }))
  const columns: TableProps<FormulaLine>['columns'] = [
    { title: '行次', dataIndex: 'lineNo', width: 64, render: (value: number) => value || '' },
    { title: '项目', dataIndex: 'name', width: 220, render: (value: string, line: FormulaLine) =>
      <Input
        aria-label={`第 ${line.lineNo} 行项目名称`}
        value={value}
        disabled={readOnly || line.rowType === 'SECTION'}
        maxLength={200}
        onChange={(event) => onLineChange(line.key, { name: event.target.value })}
      /> },
    { title: '类型', dataIndex: 'rowType', width: 100, render: (value: string) => <Tag>{value}</Tag> },
    { title: '取数公式', render: (_, line: FormulaLine) =>
      <FormulaExpressionEditor
        value={line.expression}
        onChange={(expression) => onLineChange(line.key, { expression: expressionToJson(expression) as never })}
        accounts={accounts}
        cashFlowItems={cashFlowItems}
        previousLines={previous(line.key)}
        balanceSheet={balanceSheet}
        cashFlow={cashFlow}
        aria-label={`第 ${line.lineNo} 行取数公式`}
      /> },
  ]
  return <Table<FormulaLine>
    rowKey="key"
    size="small"
    dataSource={allLines(definition)}
    columns={columns}
    pagination={false}
    scroll={{ x: 1100 }}
    locale={{ emptyText: <Typography.Text type="secondary">暂无行</Typography.Text> }}
  />
}
