import { Alert, Button, Card, Empty, Input, Space, Table, Tag, Typography } from 'antd'
import { useInfiniteQuery } from '@tanstack/react-query'
import { useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { apiData, apiHeaders, openApiClient } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'

type AuditEntry = components['schemas']['Entry']

export function AuditPage() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const [aggregateType, setAggregateType] = useState('')
  const [aggregateId, setAggregateId] = useState('')
  const normalizedType = aggregateType.trim() || undefined
  const normalizedId = aggregateId.trim() || undefined

  const query = useInfiniteQuery({
    queryKey: ['audit', ledgerId, normalizedType, normalizedId],
    initialPageParam: undefined as string | undefined,
    queryFn: ({ pageParam }) => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/audit', {
      headers: apiHeaders(session!),
      params: {
        path: { ledgerId },
        query: {
          limit: 100,
          cursor: pageParam,
          aggregateType: normalizedType,
          aggregateId: normalizedId,
        },
      },
    })),
    getNextPageParam: (page) => page.hasMore ? page.nextCursor : undefined,
    enabled: Boolean(session && ledgerId),
  })
  const entries = useMemo(() => query.data?.pages.flatMap((page) => page.items) ?? [], [query.data])

  return <section>
    <Typography.Title level={1}>审计日志</Typography.Title>
    <Space wrap style={{ marginBottom: 16 }}>
      <Input allowClear aria-label="按对象类型筛选" placeholder="对象类型" value={aggregateType}
        onChange={(event) => setAggregateType(event.target.value)} />
      <Input allowClear aria-label="按对象 ID 筛选" placeholder="对象 ID" value={aggregateId}
        onChange={(event) => setAggregateId(event.target.value)} />
    </Space>
    {query.isError && <Alert type="error" message="审计日志读取失败" />}
    <Card>
      <Table<AuditEntry>
        rowKey="id"
        loading={query.isLoading}
        dataSource={entries}
        pagination={false}
        locale={{ emptyText: <Empty description="暂无审计记录" /> }}
        scroll={{ x: 900 }}
        columns={[
          { title: '时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
          { title: '对象', render: (_: unknown, row) => <Tag>{row.aggregateType}</Tag> },
          { title: '操作', dataIndex: 'action' },
          { title: '操作者', dataIndex: 'actorId' },
          { title: '原因', dataIndex: 'reason' },
          { title: '版本', dataIndex: 'revision' },
        ]}
      />
      {query.hasNextPage && <div style={{ marginTop: 16, textAlign: 'center' }}>
        <Button loading={query.isFetchingNextPage} onClick={() => query.fetchNextPage()}>加载更多</Button>
      </div>}
    </Card>
  </section>
}
