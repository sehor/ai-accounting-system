import { Button, Segmented, Select, Space, Tag, Typography } from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { accountAmount, combination, referenceLabel, standardReference, accountReference } from '../features/reportFormulas/types'
import type { AccountReference, LineExpression } from '../features/reportFormulas/types'
import type { Account } from '../features/reportFormulas/types'

interface Props {
  value: LineExpression
  onChange: (expression: LineExpression) => void
  accounts: Account[]
  previousLines: { key: string; name: string }[]
  balanceSheet: boolean
  'aria-label'?: string
}

export function FormulaExpressionEditor({ value, onChange, accounts, previousLines, balanceSheet, ...rest }: Props) {
  if (value.type === 'LINEAR_COMBINATION') {
    return <Space direction="vertical" size={4} style={{ width: '100%' }} {...rest}>
      <Segmented
        size="small"
        value="LINEAR_COMBINATION"
        options={[{ value: 'ACCOUNT_AMOUNT', label: '科目取数' }, { value: 'LINEAR_COMBINATION', label: '前序行加减' }]}
        onChange={(type) => onChange(type === 'ACCOUNT_AMOUNT' ? accountAmount('DEBIT', []) : combination(value.components))}
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

  const setAccounts = (next: AccountReference[]) => onChange(accountAmount(value.side, next))
  return <Space direction="vertical" size={4} style={{ width: '100%' }} {...rest}>
    <Space wrap>
      <Segmented
        size="small"
        value="ACCOUNT_AMOUNT"
        options={[{ value: 'ACCOUNT_AMOUNT', label: '科目取数' }, { value: 'LINEAR_COMBINATION', label: '前序行加减' }]}
        onChange={(type) => onChange(type === 'ACCOUNT_AMOUNT' ? accountAmount(value.side, value.accounts) : combination([]))}
      />
      <Select
        aria-label="借贷方向"
        style={{ width: 110 }}
        value={value.side}
        options={[{ value: 'DEBIT', label: '借方' }, { value: 'CREDIT', label: '贷方' }]}
        onChange={(side) => onChange(accountAmount(side, value.accounts))}
      />
      {balanceSheet && <Tag>期末余额</Tag>}
    </Space>
    <Space wrap size={4}>
      {value.accounts.map((reference, index) => <Tag key={index} closable onClose={() => setAccounts(value.accounts.filter((_, i) => i !== index))}>
        {reference.type === 'STANDARD_ACCOUNT_KEY' ? '标准科目' : '账套科目'}：{referenceLabel(reference, accounts)}
      </Tag>)}
    </Space>
    <Space wrap>
      <Select
        aria-label="添加标准科目"
        style={{ minWidth: 220 }}
        placeholder="添加标准科目"
        value={null}
        showSearch
        optionFilterProp="label"
        options={[...new Set(accounts.map((account) => account.standardAccountKey).filter((key): key is string => Boolean(key)))].sort().map((key) => ({ value: key, label: key }))}
        onChange={(key: string) => setAccounts([...value.accounts, standardReference(key)])}
      />
      <Select
        aria-label="添加账套科目"
        style={{ minWidth: 220 }}
        placeholder={accounts.length ? '添加账套科目' : '暂无科目'}
        value={null}
        showSearch
        optionFilterProp="label"
        options={accounts.map((account) => ({ value: account.id, label: `${account.code} ${account.name}${account.isLeaf ? '' : '（包含下级）'}` }))}
        onChange={(id: string) => setAccounts([...value.accounts, accountReference(id)])}
      />
    </Space>
  </Space>
}
