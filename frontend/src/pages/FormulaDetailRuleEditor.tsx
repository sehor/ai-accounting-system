import { Select, Space, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { CATEGORIES, referenceLabel, standardReference, accountReference } from '../features/reportFormulas/types'
import type { DetailRule, Account, AccountReference } from '../features/reportFormulas/types'

interface Props {
  rules: DetailRule[]
  onRulesChange: (rules: DetailRule[]) => void
  accounts: Account[]
  readOnly: boolean
}

export function FormulaDetailRuleEditor({ rules, onRulesChange, accounts, readOnly }: Props) {
  const updateRule = (index: number, patch: Partial<DetailRule>) => {
    onRulesChange(rules.map((rule, i) => i === index ? { ...rule, ...patch } : rule))
  }
  const setAccounts = (index: number, accounts: AccountReference[]) => updateRule(index, { accounts })
  const columns: TableProps<DetailRule>['columns'] = [
    { title: '规则', dataIndex: 'key', width: 180, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
    { title: '方向', dataIndex: 'side', width: 100, render: (value: string, rule: DetailRule, index: number) =>
      <Select<'DEBIT' | 'CREDIT'>
        aria-label={`规则 ${rule.key} 借贷方向`}
        value={value as 'DEBIT' | 'CREDIT'}
        disabled={readOnly}
        options={[{ value: 'DEBIT', label: '借方' }, { value: 'CREDIT', label: '贷方' }]}
        onChange={(side) => updateRule(index, { side })}
      /> },
    { title: '科目类别', width: 280, render: (_, rule: DetailRule, index: number) =>
      <Select
        aria-label={`规则 ${rule.key} 科目类别`}
        mode="multiple"
        style={{ width: '100%' }}
        value={rule.categories}
        disabled={readOnly}
        options={CATEGORIES.map((category) => ({ value: category, label: category }))}
        onChange={(categories) => updateRule(index, { categories })}
      /> },
    { title: '补充科目', render: (_, rule: DetailRule, index: number) =>
      <Space direction="vertical" size={4} style={{ width: '100%' }}>
        <Space wrap size={4}>
          {rule.accounts.map((reference, accountIndex) => <Tag
            key={accountIndex}
            closable={!readOnly}
            onClose={() => setAccounts(index, rule.accounts.filter((_, i) => i !== accountIndex))}
          >
            {reference.type === 'STANDARD_ACCOUNT_KEY' ? '标准科目' : '账套科目'}：{referenceLabel(reference, accounts)}
          </Tag>)}
        </Space>
        <Space wrap>
          <Select
            aria-label={`规则 ${rule.key} 添加标准科目`}
            style={{ minWidth: 200 }}
            placeholder="添加标准科目"
            value={null}
            disabled={readOnly}
            showSearch
            optionFilterProp="label"
            options={[...new Set(accounts.map((account) => account.standardAccountKey).filter((key): key is string => Boolean(key)))].sort().map((key) => ({ value: key, label: key }))}
            onChange={(key: string) => setAccounts(index, [...rule.accounts, standardReference(key)])}
          />
          <Select
            aria-label={`规则 ${rule.key} 添加账套科目`}
            style={{ minWidth: 200 }}
            placeholder={accounts.length ? '添加账套科目' : '暂无科目'}
            value={null}
            disabled={readOnly}
            showSearch
            optionFilterProp="label"
            options={accounts.map((account) => ({ value: account.id, label: `${account.code} ${account.name}${account.isLeaf ? '' : '（包含下级）'}` }))}
            onChange={(id: string) => setAccounts(index, [...rule.accounts, accountReference(id)])}
          />
        </Space>
      </Space> },
  ]
  return <Table<DetailRule>
    rowKey="key"
    size="small"
    dataSource={rules}
    columns={columns}
    pagination={false}
    scroll={{ x: 1000 }}
  />
}
