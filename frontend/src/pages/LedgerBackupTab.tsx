import { DownloadOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQueryClient } from '@tanstack/react-query'
import { Alert, Button, Card, Form, Input, Space, Typography, Upload, message } from 'antd'
import { useState } from 'react'
import { ApiError, apiData, apiHeaders, openApiClient, type ApiAuth } from '../api/client'
import type { components } from '../api/generated'

type Ledger = components['schemas']['LedgerResponse']
type LedgerRole = components['schemas']['Member']['role']

const MAX_BACKUP_BYTES = 100 * 1024 * 1024

export function backupFileError(file: File): string | null {
  if (!file.name.toLowerCase().endsWith('.aibackup')) return '请选择 .aibackup 账套备份文件'
  if (file.size > MAX_BACKUP_BYTES) return '账套备份文件不能超过 100 MiB'
  return null
}

export function LedgerBackupTab({ ledgerId, session, role, onRestored }: {
  ledgerId: string
  session: ApiAuth
  role?: LedgerRole
  onRestored: (ledger: Ledger) => void
}) {
  const client = useQueryClient()
  const [messageApi, contextHolder] = message.useMessage()
  const [file, setFile] = useState<File | null>(null)
  const [form] = Form.useForm<{ name?: string }>()

  const download = useMutation({
    mutationFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/backup', { params: { path: { ledgerId } }, headers: apiHeaders(session), parseAs: 'blob' })),
    onSuccess: (blob) => {
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = `ledger-${ledgerId}.aibackup`
      anchor.click()
      URL.revokeObjectURL(url)
      messageApi.success('账套备份已生成')
    },
    onError: (error) => messageApi.error(errorText(error, '账套备份失败')),
  })

  const restore = useMutation({
    mutationFn: ({ archive, name }: { archive: File; name?: string }) => {
      const body = new FormData()
      body.append('file', archive)
      if (name?.trim()) body.append('name', name.trim())
      return apiData(openApiClient.POST('/v1/ledger-restores', {
        headers: apiHeaders(session), body: { file: archive as unknown as string, name: name?.trim() || undefined }, bodySerializer: () => body,
      }))
    },
    onSuccess: (ledger) => {
      messageApi.success('账套恢复完成')
      setFile(null)
      form.resetFields()
      void client.invalidateQueries({ queryKey: ['ledgers'] })
      onRestored(ledger)
    },
    onError: (error) => messageApi.error(errorText(error, '账套恢复失败')),
  })

  const submit = ({ name }: { name?: string }) => {
    if (!file) {
      messageApi.warning('请先选择账套备份文件')
      return
    }
    restore.mutate({ archive: file, name })
  }

  return <>{contextHolder}<Space direction="vertical" size={16} style={{ width: '100%' }}>
    <Alert showIcon type="info" message="恢复会创建新账套，不会覆盖当前账套"
      description="备份包含业务数据和附件，但不复制成员。恢复后当前用户是新账套唯一 OWNER。" />
    <Card title="备份当前账套">
      <Space direction="vertical">
        <Typography.Text type="secondary">仅 OWNER 可以生成备份，最大 100 MiB。</Typography.Text>
        <Button icon={<DownloadOutlined />} loading={download.isPending}
          disabled={role !== 'OWNER'} onClick={() => download.mutate()}>
          下载账套备份
        </Button>
      </Space>
    </Card>
    <Card title="恢复为新账套">
      <Form form={form} layout="vertical" onFinish={submit} style={{ maxWidth: 520 }}>
        <Form.Item label="新账套名称（可选）" name="name"
          rules={[{ max: 200, message: '账套名称不能超过 200 个字符' }]}>
          <Input placeholder="留空则使用原账套名称并加“（恢复）”" maxLength={200} />
        </Form.Item>
        <Form.Item label="账套备份文件" required>
          <Upload accept=".aibackup" maxCount={1} beforeUpload={(selected) => {
            const error = backupFileError(selected)
            if (error) {
              messageApi.error(error)
              return Upload.LIST_IGNORE
            }
            setFile(selected)
            return false
          }} onRemove={() => { setFile(null) }}>
            <Button icon={<UploadOutlined />}>选择 .aibackup 文件</Button>
          </Upload>
        </Form.Item>
        <Button type="primary" htmlType="submit" disabled={!file} loading={restore.isPending}>
          恢复为新账套
        </Button>
      </Form>
    </Card>
  </Space></>
}

function errorText(error: unknown, fallback: string): string {
  return error instanceof ApiError ? error.message : fallback
}
