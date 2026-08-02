import { Button, Card, Empty, Form, Input, Modal, Select, Table, Tag, Typography, DatePicker, Switch } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import { apiFetch, jsonBody } from '../api/client'
import type { AccountingStandard, Ledger } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

interface CreateLedgerForm {
  name: string
  standard: string
  startDate: Dayjs
  approvalEnabled?: boolean
  separator: '.' | '-'
  level2Width: number
  level3Width: number
  level4Width: number
}

export function LedgerListPage() {
  const { session } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const ledgers = useQuery({ queryKey: ['ledgers'], queryFn: () => apiFetch<Ledger[]>('/ledgers', session!), enabled: Boolean(session) })
  const standards = useQuery({ queryKey: ['accounting-standards'], queryFn: () => apiFetch<AccountingStandard[]>('/accounting-standards', session!), enabled: Boolean(session) })
  const create = useMutation({
    mutationFn: (value: CreateLedgerForm) => {
      const [accountingStandardCode, accountingStandardVersion] = value.standard.split('/')
      return apiFetch<Ledger>('/ledgers', session!, { method: 'POST', body: jsonBody({
        name: value.name,
        accountingStandardCode,
        accountingStandardVersion,
        baseCurrency: 'CNY',
        startDate: value.startDate.format('YYYY-MM-DD'),
        approvalEnabled: Boolean(value.approvalEnabled),
        accountCodeRule: {
          separator: value.separator,
          level2Width: value.level2Width,
          level3Width: value.level3Width,
          level4Width: value.level4Width,
        },
      }) })
    },
    onSuccess: (ledger) => { void queryClient.invalidateQueries({ queryKey: ['ledgers'] }); setOpen(false); navigate(`/ledgers/${ledger.id}/overview`) },
  })

  return <>
    <div className="page-heading"><div><Typography.Title level={1}>账套</Typography.Title><Typography.Text type="secondary">管理可访问的企业账套</Typography.Text></div><Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>新建账套</Button></div>
    <Card>
      <Table className="financial-table" rowKey="id" loading={ledgers.isLoading} dataSource={ledgers.data || []} locale={{ emptyText: <Empty description="还没有账套" /> }} columns={[
        { title: '账套名称', dataIndex: 'name', render: (name: string, record: Ledger) => <Button type="link" onClick={() => navigate(`/ledgers/${record.id}/overview`)}>{name}</Button> },
        { title: '会计准则', render: (_: unknown, record: Ledger) => `${record.accountingStandardCode} / ${record.accountingStandardVersion}` },
        { title: '本位币', dataIndex: 'baseCurrency' },
        { title: '启用审批', dataIndex: 'approvalEnabled', render: (enabled: boolean) => <Tag color={enabled ? 'blue' : 'default'}>{enabled ? '是' : '否'}</Tag> },
        { title: '状态', dataIndex: 'status', render: (status: string) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status === 'ACTIVE' ? '正常' : status}</Tag> },
      ]} />
    </Card>
    <Modal title="新建账套" open={open} footer={null} onCancel={() => setOpen(false)} destroyOnClose>
      <Form layout="vertical" onFinish={(value) => create.mutate(value as CreateLedgerForm)} initialValues={{ standard: 'SME/2011-17', startDate: dayjs(), approvalEnabled: false, separator: '.', level2Width: 2, level3Width: 2, level4Width: 2 }}>
        <Form.Item name="name" label="账套名称" rules={[{ required: true, message: '请输入账套名称' }]}><Input autoFocus /></Form.Item>
        <Form.Item name="standard" label="会计准则版本" rules={[{ required: true }]}>
          <Select loading={standards.isLoading} options={(standards.data || []).map((standard) => ({
            value: `${standard.code}/${standard.version}`,
            label: `${standard.name}（${standard.code}/${standard.version}）`,
          }))} />
        </Form.Item>
        <Form.Item label="科目编码规则" required>
          <Input.Group compact>
            <Form.Item name="separator" noStyle><Select aria-label="科目分隔符" style={{ width: '28%' }} options={[{ value: '.', label: '分隔符 .' }, { value: '-', label: '分隔符 -' }]} /></Form.Item>
            {(['level2Width', 'level3Width', 'level4Width'] as const).map((name, index) =>
              <Form.Item key={name} name={name} noStyle rules={[{ required: true }]}>
                <Select aria-label={`${index + 2}级科目段宽`} style={{ width: '24%' }} options={[1, 2, 3, 4, 5, 6, 7, 8].map((value) => ({ value, label: `${index + 2}级 ${value} 位` }))} />
              </Form.Item>)}
          </Input.Group>
        </Form.Item>
        <Form.Item label="本位币"><Input value="人民币（CNY）" disabled /></Form.Item>
        <Form.Item name="startDate" label="启用日期" rules={[{ required: true, message: '请选择启用日期' }]}><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="approvalEnabled" label="启用凭证审批" valuePropName="checked"><Switch /></Form.Item>
        <Button type="primary" htmlType="submit" loading={create.isPending} block>创建并初始化</Button>
      </Form>
    </Modal>
  </>
}
