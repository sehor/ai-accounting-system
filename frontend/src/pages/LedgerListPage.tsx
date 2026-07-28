import { Button, Card, Empty, Form, Input, Modal, Table, Tag, Typography, DatePicker, Switch } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import { apiFetch, jsonBody } from '../api/client'
import type { Ledger } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

interface CreateLedgerForm { name: string; startDate: Dayjs; approvalEnabled?: boolean }

export function LedgerListPage() {
  const { session } = useAuth()
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  const [open, setOpen] = useState(false)
  const ledgers = useQuery({ queryKey: ['ledgers'], queryFn: () => apiFetch<Ledger[]>('/ledgers', session!), enabled: Boolean(session) })
  const create = useMutation({
    mutationFn: (value: CreateLedgerForm) => apiFetch<Ledger>('/ledgers', session!, { method: 'POST', body: jsonBody({ name: value.name, accountingStandardCode: 'SME', accountingStandardVersion: 'v1', baseCurrency: 'CNY', startDate: value.startDate.format('YYYY-MM-DD'), approvalEnabled: Boolean(value.approvalEnabled) }) }),
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
      <Form layout="vertical" onFinish={(value) => create.mutate(value as CreateLedgerForm)} initialValues={{ startDate: dayjs(), approvalEnabled: false }}>
        <Form.Item name="name" label="账套名称" rules={[{ required: true, message: '请输入账套名称' }]}><Input autoFocus /></Form.Item>
        <Form.Item label="会计准则"><Input value="小企业会计准则 v1" disabled /></Form.Item>
        <Form.Item label="本位币"><Input value="人民币（CNY）" disabled /></Form.Item>
        <Form.Item name="startDate" label="启用日期" rules={[{ required: true, message: '请选择启用日期' }]}><DatePicker style={{ width: '100%' }} /></Form.Item>
        <Form.Item name="approvalEnabled" label="启用凭证审批" valuePropName="checked"><Switch /></Form.Item>
        <Button type="primary" htmlType="submit" loading={create.isPending} block>创建并初始化</Button>
      </Form>
    </Modal>
  </>
}
