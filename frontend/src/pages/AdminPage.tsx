import { Alert, App, Button, Card, Form, Modal, Popconfirm, Select, Space, Switch, Table, Tabs, Tag, Typography } from 'antd'
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiFetch, jsonBody } from '../api/client'
import type { AdminLedger, AdminUser, LedgerRole, Member } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

interface PermissionForm {
  userId: string
  role: LedgerRole
}

const roleOptions: { value: LedgerRole; label: string }[] = [
  { value: 'OWNER', label: '所有者' },
  { value: 'EDITOR', label: '编辑' },
  { value: 'REVIEWER', label: '审核' },
  { value: 'VIEWER', label: '只读' },
]

export function AdminPage() {
  const { session } = useAuth()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
  const [showDeleted, setShowDeleted] = useState(false)
  const [permissionLedger, setPermissionLedger] = useState<AdminLedger | null>(null)
  const [permissionForm] = Form.useForm<PermissionForm>()
  const users = useQuery({
    queryKey: ['admin-users'],
    queryFn: () => apiFetch<AdminUser[]>('/admin/users', session!),
    enabled: Boolean(session),
  })
  const ledgers = useQuery({
    queryKey: ['admin-ledgers'],
    queryFn: () => apiFetch<AdminLedger[]>('/admin/ledgers', session!),
    enabled: Boolean(session),
  })
  const members = useQuery({
    queryKey: ['ledger-members', permissionLedger?.id],
    queryFn: () => apiFetch<Member[]>(`/ledgers/${permissionLedger!.id}/members`, session!),
    enabled: Boolean(session && permissionLedger),
  })

  const deleteUser = useMutation({
    mutationFn: (userId: string) => apiFetch<void>(`/admin/users/${userId}`, session!, { method: 'DELETE' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      void message.success('用户已删除')
    },
  })
  const restoreUser = useMutation({
    mutationFn: (userId: string) => apiFetch<AdminUser>(`/admin/users/${userId}:restore`, session!, { method: 'POST' }),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['admin-users'] })
      void message.success('用户已恢复')
    },
  })
  const deleteLedger = useMutation({
    mutationFn: (ledgerId: string) => apiFetch<void>(`/admin/ledgers/${ledgerId}`, session!, { method: 'DELETE' }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin-ledgers'] }),
        queryClient.invalidateQueries({ queryKey: ['ledgers'] }),
      ])
      void message.success('账套已删除')
    },
  })
  const restoreLedger = useMutation({
    mutationFn: (ledgerId: string) => apiFetch<AdminLedger>(`/admin/ledgers/${ledgerId}:restore`, session!, { method: 'POST' }),
    onSuccess: async () => {
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['admin-ledgers'] }),
        queryClient.invalidateQueries({ queryKey: ['ledgers'] }),
      ])
      void message.success('账套已恢复')
    },
  })
  const assignPermission = useMutation({
    mutationFn: (value: PermissionForm) => apiFetch<Member>(
      `/ledgers/${permissionLedger!.id}/members`,
      session!,
      { method: 'POST', body: jsonBody(value) },
    ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['ledger-members', permissionLedger?.id] })
      permissionForm.resetFields()
      void message.success('账套权限已分配')
    },
  })
  const removePermission = useMutation({
    mutationFn: (userId: string) => apiFetch<void>(
      `/ledgers/${permissionLedger!.id}/members/${userId}`,
      session!,
      { method: 'DELETE' },
    ),
    onSuccess: async () => {
      await queryClient.invalidateQueries({ queryKey: ['ledger-members', permissionLedger?.id] })
      void message.success('账套权限已移除')
    },
  })

  const visibleUsers = (users.data || []).filter(user => showDeleted || !user.deleted)
  const visibleLedgers = (ledgers.data || []).filter(ledger => showDeleted || !ledger.deleted)
  const assignableUsers = (users.data || []).filter(user =>
    !user.deleted && !user.protectedUser && user.userType === 'HUMAN')

  return <>
    <div className="page-heading">
      <div>
        <Typography.Title level={1}>平台管理</Typography.Title>
        <Typography.Text type="secondary">管理系统中的全部用户和账套</Typography.Text>
      </div>
      <Space><Typography.Text>显示已删除</Typography.Text><Switch checked={showDeleted} onChange={setShowDeleted} /></Space>
    </div>
    {(users.error || ledgers.error) && <Alert type="error" showIcon message="无法加载平台管理数据" description="请确认当前登录账户为 admin。" />}
    <Card>
      <Tabs items={[
        {
          key: 'users',
          label: `用户（${users.data?.length || 0}）`,
          children: <Table<AdminUser>
            rowKey="id"
            loading={users.isLoading}
            dataSource={visibleUsers}
            pagination={{ pageSize: 20 }}
            columns={[
              { title: '用户名', dataIndex: 'displayName', render: (value: string | null) => value || '-' },
              { title: '类型', dataIndex: 'userType' },
              { title: '来源', dataIndex: 'issuer' },
              { title: '邮箱', dataIndex: 'email', render: (value: string | null) => value || '-' },
              {
                title: '状态',
                render: (_: unknown, user: AdminUser) => user.deleted
                  ? <Tag>已删除</Tag>
                  : <Tag color="green">正常</Tag>,
              },
              {
                title: '操作',
                render: (_: unknown, user: AdminUser) => user.deleted
                  ? <Button icon={<ReloadOutlined />} loading={restoreUser.isPending}
                      onClick={() => restoreUser.mutate(user.id)}>恢复</Button>
                  : <Popconfirm title={`确定删除用户“${user.displayName || user.id}”吗？`}
                      okText="确认" cancelText="取消"
                      onConfirm={() => deleteUser.mutate(user.id)} disabled={user.protectedUser}>
                      <Button danger icon={<DeleteOutlined />} disabled={user.protectedUser}
                        loading={deleteUser.isPending}>删除</Button>
                    </Popconfirm>,
              },
            ]}
          />,
        },
        {
          key: 'ledgers',
          label: `账套（${ledgers.data?.length || 0}）`,
          children: <Table<AdminLedger>
            rowKey="id"
            loading={ledgers.isLoading}
            dataSource={visibleLedgers}
            pagination={{ pageSize: 20 }}
            columns={[
              { title: '账套名称', dataIndex: 'name' },
              { title: '会计准则', render: (_: unknown, ledger: AdminLedger) => `${ledger.accountingStandardCode} / ${ledger.accountingStandardVersion}` },
              { title: '启用日期', dataIndex: 'startDate' },
              {
                title: '状态',
                render: (_: unknown, ledger: AdminLedger) => ledger.deleted
                  ? <Tag>已删除</Tag>
                  : <Tag color="green">正常</Tag>,
              },
              {
                title: '操作',
                render: (_: unknown, ledger: AdminLedger) => ledger.deleted
                  ? <Button icon={<ReloadOutlined />} loading={restoreLedger.isPending}
                      onClick={() => restoreLedger.mutate(ledger.id)}>恢复</Button>
                  : <Space>
                      <Button onClick={() => setPermissionLedger(ledger)}>分配权限</Button>
                      <Popconfirm title={`确定删除账套“${ledger.name}”吗？`}
                        okText="确认" cancelText="取消"
                        onConfirm={() => deleteLedger.mutate(ledger.id)}>
                        <Button danger icon={<DeleteOutlined />} loading={deleteLedger.isPending}>删除</Button>
                      </Popconfirm>
                    </Space>,
              },
            ]}
          />,
        },
      ]} />
    </Card>
    <Modal title={`分配账套权限：${permissionLedger?.name || ''}`} open={Boolean(permissionLedger)}
      footer={null} width={720} destroyOnHidden onCancel={() => setPermissionLedger(null)}>
      <Form form={permissionForm} layout="inline" initialValues={{ role: 'EDITOR' }}
        onFinish={(value) => assignPermission.mutate(value)} style={{ marginBottom: 20 }}>
        <Form.Item name="userId" label="用户" rules={[{ required: true, message: '请选择用户' }]}>
          <Select showSearch optionFilterProp="label" style={{ width: 240 }} placeholder="选择同事"
            options={assignableUsers.map(user => ({ value: user.id, label: user.displayName || user.email || user.id }))} />
        </Form.Item>
        <Form.Item name="role" label="权限" rules={[{ required: true }]}>
          <Select style={{ width: 120 }} options={roleOptions} />
        </Form.Item>
        <Button type="primary" htmlType="submit" loading={assignPermission.isPending}>分配</Button>
      </Form>
      <Table<Member> rowKey="userId" size="small" loading={members.isLoading}
        dataSource={members.data || []} pagination={false} columns={[
          { title: '用户', render: (_: unknown, member: Member) => member.displayName || member.email || member.userId },
          { title: '权限', dataIndex: 'role', render: (role: LedgerRole) => roleOptions.find(option => option.value === role)?.label || role },
          { title: '状态', dataIndex: 'status', render: (status: string) => <Tag color={status === 'ACTIVE' ? 'green' : 'default'}>{status}</Tag> },
          { title: '操作', render: (_: unknown, member: Member) => <Popconfirm
              title="确定移除该用户的账套权限吗？" okText="确认" cancelText="取消"
              onConfirm={() => removePermission.mutate(member.userId)}>
              <Button danger size="small" loading={removePermission.isPending}>移除</Button>
            </Popconfirm> },
        ]} />
    </Modal>
  </>
}
