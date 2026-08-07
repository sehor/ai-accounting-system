import { Alert, App, Button, Card, Popconfirm, Table, Tabs, Tag, Typography } from 'antd'
import { DeleteOutlined, ReloadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { apiFetch } from '../api/client'
import type { AdminLedger, AdminUser } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

export function AdminPage() {
  const { session } = useAuth()
  const { message } = App.useApp()
  const queryClient = useQueryClient()
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

  return <>
    <div className="page-heading">
      <div>
        <Typography.Title level={1}>平台管理</Typography.Title>
        <Typography.Text type="secondary">管理系统中的全部用户和账套</Typography.Text>
      </div>
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
            dataSource={users.data || []}
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
            dataSource={ledgers.data || []}
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
                  : <Popconfirm title={`确定删除账套“${ledger.name}”吗？`}
                      okText="确认" cancelText="取消"
                      onConfirm={() => deleteLedger.mutate(ledger.id)}>
                      <Button danger icon={<DeleteOutlined />} loading={deleteLedger.isPending}>删除</Button>
                    </Popconfirm>,
              },
            ]}
          />,
        },
      ]} />
    </Card>
  </>
}
