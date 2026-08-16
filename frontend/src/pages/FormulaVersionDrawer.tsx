import { Button, Drawer, Popconfirm, Space, Table, Tag, Typography } from 'antd'
import type { TableProps } from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { apiData, apiHeaders, openApiClient, ApiError } from '../api/client'
import type { components } from '../api/generated'
import type { ApiAuth } from '../api/client'

type VersionInfo = components['schemas']['ReportFormulaVersionInfo']
type VersionPage = components['schemas']['ReportFormulaVersionPage']

interface Props {
  ledgerId: string
  code: string
  auth: ApiAuth
  open: boolean
  onClose: () => void
  hasDraft: boolean
  expectedPublishedVersion: number
  onChanged: () => void
}

const sourceLabels: Record<string, string> = {
  STANDARD: '标准',
  MIGRATION: '迁移',
  USER: '用户发布',
  ROLLBACK: '回滚',
}

export function FormulaVersionDrawer({ ledgerId, code, auth, open, onClose, hasDraft, expectedPublishedVersion, onChanged }: Props) {
  const client = useQueryClient()
  const [page, setPage] = useState(1)
  const pageSize = 10
  const versions = useQuery({
    queryKey: ['report-formula-versions', ledgerId, code, page],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/report-formulas/{code}/versions', {
      params: { path: { ledgerId, code }, query: { page, pageSize } },
      headers: apiHeaders(auth),
    })),
    enabled: open && Boolean(auth),
  })
  const rollback = useMutation({
    mutationFn: (version: number) => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/report-formulas/{code}/versions/{version}:rollback', {
      params: { path: { ledgerId, code, version } },
      headers: apiHeaders(auth),
      body: { expectedPublishedVersion },
    })),
    onSuccess: () => {
      void client.invalidateQueries({ queryKey: ['report-formula', ledgerId, code] })
      void client.invalidateQueries({ queryKey: ['report-formula-versions', ledgerId, code] })
      void client.invalidateQueries({ queryKey: ['report', ledgerId] })
      onChanged()
      onClose()
    },
    onError: (error) => {
      const problem = error instanceof ApiError ? error.problem : undefined
      window.alert(problem?.detail || '回滚失败')
    },
  })
  const columns: TableProps<VersionInfo>['columns'] = [
    { title: '版本', dataIndex: 'version', width: 80 },
    { title: '来源', dataIndex: 'source', width: 100, render: (value: string) => <Tag>{sourceLabels[value] || value}</Tag> },
    { title: '回滚自', dataIndex: 'rollbackOfVersion', width: 90, render: (value: number | undefined) => value ?? '' },
    { title: '发布时间', dataIndex: 'createdAt', render: (value: string) => new Date(value).toLocaleString('zh-CN') },
    { title: '操作', width: 120, render: (_, row: VersionInfo) =>
      <Popconfirm
        title={`回滚到版本 ${row.version}？`}
        description={hasDraft ? '存在草稿时无法回滚，请先放弃草稿。' : `将生成新的发布版本 ${expectedPublishedVersion + 1}。`}
        disabled={hasDraft || row.version === expectedPublishedVersion}
        onConfirm={() => rollback.mutate(row.version)}
      >
        <Button size="small" disabled={hasDraft || row.version === expectedPublishedVersion} loading={rollback.isPending}>回滚</Button>
      </Popconfirm> },
  ]
  const data: VersionPage | undefined = versions.data
  return <Drawer
    title={`公式版本历史 · ${code}`}
    width={720}
    open={open}
    onClose={onClose}
    destroyOnClose
  >
    {hasDraft && <Typography.Paragraph type="warning">存在草稿：回滚会生成新的发布版本，但必须先放弃草稿。</Typography.Paragraph>}
    <Table<VersionInfo>
      rowKey="version"
      size="small"
      loading={versions.isLoading}
      dataSource={data?.items || []}
      columns={columns}
      pagination={{
        current: page,
        pageSize,
        total: data?.totalItems || 0,
        onChange: setPage,
        showSizeChanger: false,
      }}
    />
  </Drawer>
}
