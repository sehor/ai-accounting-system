import { Button, Segmented, Select, Space, Tag, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import {
  accountAmount, cashFlowItemAmount, combination, referenceLabel, standardReference, accountReference,
} from '../features/reportFormulas/types'
import type {
  AccountReference, AmountBasis, CashFlowDirection, LineExpression,
} from '../features/reportFormulas/types'
import type { Account } from '../features/reportFormulas/types'
import type { components } from '../api/generated'

type CashFlowItem = components['schemas']['LedgerCashFlowItem']

interface Props {
  value: LineExpression
  onChange: (expression: LineExpression) => void
  accounts: Account[]
  cashFlowItems?: CashFlowItem[]
  previousLines: { key: string; name: string }[]
  balanceSheet: boolean
  cashFlow?: boolean
  'aria-label'?: string
}

function typeOptions(cashFlow: boolean): { value: string; label: string }[] {
  return [
    { value: 'ACCOUNT_AMOUNT', label: '科目取数' },
    { value: 'LINEAR_COMBINATION', label: '前序行加减' },
    ...(cashFlow ? [{ value: 'CASH_FLOW_ITEM_AMOUNT', label: '现金流项目' }] : []),
  ]
}

function AccountReferencePicker({ references, onChange, accounts, ariaLabel }: {
  references: AccountReference[]
  onChange: (references: AccountReference[]) => void
  accounts: Account[]
  ariaLabel: string
}) {
  return <Space wrap size={4}>
    {references.map((reference, index) => <Tag key={index} closable onClose={() => onChange(references.filter((_, i) => i !== index))}>
      {reference.type === 'STANDARD_ACCOUNT_KEY' ? '标准科目' : '账套科目'}：{referenceLabel(reference, accounts)}
    </Tag>)}
    <Select
      aria-label={`添加${ariaLabel}标准科目`}
      style={{ minWidth: 200 }}
      placeholder="添加标准科目"
      value={null}
      showSearch
      optionFilterProp="label"
      options={[...new Set(accounts.map((account) => account.standardAccountKey).filter((key): key is string => Boolean(key)))].sort().map((key) => ({ value: key, label: key }))}
      onChange={(key: string) => onChange([...references, standardReference(key)])}
    />
    <Select
      aria-label={`添加${ariaLabel}账套科目`}
      style={{ minWidth: 200 }}
      placeholder={accounts.length ? '添加账套科目' : '暂无科目'}
      value={null}
      showSearch
      optionFilterProp="label"
      options={accounts.map((account) => ({ value: account.id, label: `${account.code} ${account.name}${account.isLeaf ? '' : '（包含下级）'}` }))}
      onChange={(id: string) => onChange([...references, accountReference(id)])}
    />
  </Space>
}

export function FormulaExpressionEditor({ value, onChange, accounts, cashFlowItems = [], previousLines, balanceSheet, cashFlow = false, ...rest }: Props) {
  if (value.type === 'CASH_FLOW_ITEM_AMOUNT') {
    return <Space direction="vertical" size={4} style={{ width: '100%' }} {...rest}>
      <Segmented
        size="small"
        value="CASH_FLOW_ITEM_AMOUNT"
        options={typeOptions(cashFlow)}
        onChange={(type) => {
          if (type === 'ACCOUNT_AMOUNT') onChange(accountAmount('DEBIT', []))
          else if (type === 'LINEAR_COMBINATION') onChange(combination([]))
          else onChange(cashFlowItemAmount(value.direction, value.itemCodes, value.cashAccounts))
        }}
      />
      <Space wrap>
        <Select
          aria-label="现金流方向"
          style={{ width: 110 }}
          value={value.direction}
          options={[
            { value: 'INFLOW', label: '流入' },
            { value: 'OUTFLOW', label: '流出' },
            { value: 'NET', label: '净额' },
          ]}
          onChange={(direction) => onChange(cashFlowItemAmount(direction as CashFlowDirection, value.itemCodes, value.cashAccounts))}
        />
        <Select
          aria-label="现金流项目"
          mode="multiple"
          style={{ minWidth: 280 }}
          placeholder={cashFlowItems.length ? '选择现金流项目' : '暂无现金流项目'}
          value={value.itemCodes}
          options={cashFlowItems
            .filter((item) => item.status === 'ACTIVE')
            .map((item) => ({ value: item.code, label: `${item.code} ${item.name}` }))}
          onChange={(itemCodes) => onChange(cashFlowItemAmount(value.direction, itemCodes as string[], value.cashAccounts))}
        />
      </Space>
      <AccountReferencePicker
        references={value.cashAccounts}
        onChange={(cashAccounts) => onChange(cashFlowItemAmount(value.direction, value.itemCodes, cashAccounts))}
        accounts={accounts}
        ariaLabel="现金"
      />
    </Space>
  }

  if (value.type === 'LINEAR_COMBINATION') {
    return <Space direction="vertical" size={4} style={{ width: '100%' }} {...rest}>
      <Segmented
        size="small"
        value="LINEAR_COMBINATION"
        options={typeOptions(cashFlow)}
        onChange={(type) => {
          if (type === 'ACCOUNT_AMOUNT') onChange(accountAmount('DEBIT', [], undefined))
          else if (type === 'LINEAR_COMBINATION') onChange(combination(value.components))
          else onChange(cashFlowItemAmount('INFLOW', [], []))
        }}
      />
      <Typography.Text type="secondary">下拉选项只能选择当前行之前的行</Typography.Text>
      {value.components.map((component, index) => <Space key={index} wrap>
        <Select
          aria-label="前序行"
          style={{ minWidth: 200 }}
          value={component.lineKey || undefined}
          options={previousLines.map((line) => ({ value: line.key, label: `${line.key} ${line.name}` }))}
          onChange={(lineKey) => {
            const components = [...value.components]
            components[index] = { ...component, lineKey }
            onChange(combination(components))
          }}
        />
        <Select
          aria-label="系数"
          style={{ width: 90 }}
          value={component.factor}
          options={[{ value: 1, label: '加' }, { value: -1, label: '减' }]}
          onChange={(factor) => {
            const components = [...value.components]
            components[index] = { ...component, factor }
            onChange(combination(components))
          }}
        />
        <Button type="text" danger aria-label="删除组件" icon={<DeleteOutlined />} onClick={() => onChange(combination(value.components.filter((_, i) => i !== index)))} />
      </Space>)}
      <Button size="small" icon={<PlusOutlined />} onClick={() => onChange(combination([...value.components, { lineKey: previousLines[0]?.key || '', factor: 1 }]))} disabled={previousLines.length === 0}>
        添加前序行
      </Button>
    </Space>
  }

  const setAccounts = (next: AccountReference[]) => onChange(accountAmount(value.side, next, value.basis))
  return <Space direction="vertical" size={4} style={{ width: '100%' }} {...rest}>
    <Space wrap>
      <Segmented
        size="small"
        value="ACCOUNT_AMOUNT"
        options={typeOptions(cashFlow)}
        onChange={(type) => {
          if (type === 'ACCOUNT_AMOUNT') onChange(accountAmount(value.side, value.accounts, value.basis))
          else if (type === 'LINEAR_COMBINATION') onChange(combination([]))
          else onChange(cashFlowItemAmount('INFLOW', [], []))
        }}
      />
      <Select
        aria-label="借贷方向"
        style={{ width: 110 }}
        value={value.side}
        options={[{ value: 'DEBIT', label: '借方' }, { value: 'CREDIT', label: '贷方' }]}
        onChange={(side) => onChange(accountAmount(side as 'DEBIT' | 'CREDIT', value.accounts, value.basis))}
      />
      {balanceSheet && <Tag>期末余额</Tag>}
      {cashFlow && value.operation === 'ACCOUNT_BALANCE' && <Select
        aria-label="余额基准"
        style={{ width: 120 }}
        value={value.basis ?? undefined}
        placeholder="列默认基准"
        allowClear
        options={[
          { value: 'OPENING', label: '期初余额' },
          { value: 'CLOSING', label: '期末余额' },
        ]}
        onChange={(basis) => onChange(accountAmount(value.side, value.accounts, basis as AmountBasis | undefined))}
      />}
    </Space>
    <AccountReferencePicker
      references={value.accounts}
      onChange={setAccounts}
      accounts={accounts}
      ariaLabel="科目"
    />
  </Space>
}
