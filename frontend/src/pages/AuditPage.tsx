import { Alert, Card, Empty, Table, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { AuditEntry } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

export function AuditPage() {
  const { ledgerId = '' } = useParams(); const { session } = useAuth(); const query = useQuery({ queryKey: ['audit', ledgerId], queryFn: () => apiFetch<AuditEntry[]>(`/ledgers/${ledgerId}/audit`, session!), enabled: Boolean(session && ledgerId) })
  return <><Typography.Title level={1}>审计日志</Typography.Title>{query.isError && <Alert type="error" message="审计日志读取失败" />}<Card><Table rowKey="id" loading={query.isLoading} dataSource={query.data || []} locale={{ emptyText: <Empty description="暂无审计记录" /> }} scroll={{ x: 900 }} columns={[{ title: '时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') }, { title: '对象', render: (_: unknown, row: AuditEntry) => <Tag>{row.aggregateType}</Tag> }, { title: '操作', dataIndex: 'action' }, { title: '操作者', dataIndex: 'actorId' }, { title: '原因', dataIndex: 'reason' }, { title: '版本', dataIndex: 'revision' }]} /></Card></>
}
