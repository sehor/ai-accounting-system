import { useMutation } from '@tanstack/react-query'
import { Alert, App, Button, Form, Input, Modal, Radio, Select, Space, Table, Typography } from 'antd'
import { useRef, useState } from 'react'
import { ApiError, apiData, apiHeaders, createIdempotencyKey, openApiClient } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'

type Period = components['schemas']['Period']
type Request = components['schemas']['VoucherRenumberRequest']
type Preview = components['schemas']['VoucherRenumberPreview']

type Props = {
  ledgerId: string
  periods: Period[]
  initialPeriodId?: string
  onCancel: () => void
  onComplete: (periodId: string) => void
}

export function VoucherRenumberModal({ ledgerId, periods, initialPeriodId, onCancel, onComplete }: Props) {
  const { session } = useAuth()
  const { message } = App.useApp()
  const [periodId, setPeriodId] = useState(initialPeriodId)
  const [mode, setMode] = useState<Request['mode']>('DATE_ORDER')
  const [startNumber, setStartNumber] = useState('1')
  const [preview, setPreview] = useState<Preview | null>(null)
  const [error, setError] = useState('')
  const executionKey = useRef('')
  const period = periods.find((item) => item.id === periodId)
  const validNumber = /^[1-9][0-9]{0,18}$/.test(startNumber)
  const invalidatePreview = () => { setPreview(null); setError(''); executionKey.current = '' }

  const previewMutation = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/renumber:preview', {
      headers: apiHeaders(session!), params: { path: { ledgerId } },
      body: { periodId: periodId!, mode, startNumber },
    })),
    onSuccess: (result) => { setPreview(result); executionKey.current = createIdempotencyKey(); setError('') },
    onError: (failure: Error) => { setPreview(null); setError(failure.message) },
  })
  const executeMutation = useMutation({
    mutationFn: () => {
      const headers = new Headers(apiHeaders(session!))
      headers.set('Idempotency-Key', executionKey.current)
      return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/renumber:execute', {
        headers, params: { path: { ledgerId } },
        body: { periodId: periodId!, mode, startNumber, previewFingerprint: preview!.previewFingerprint },
      }))
    },
    onSuccess: (result) => {
      message.success(result.changedCount ? `已整理 ${result.changedCount} 张凭证` : '当前期间无需整理凭证号')
      onComplete(result.periodId)
    },
    onError: (failure: Error) => {
      if (failure instanceof ApiError && [403, 404, 409, 422].includes(failure.status)) setPreview(null)
      // Keep the same key after an uncertain network result so retry cannot execute twice.
      setError(failure.message)
    },
  })
  const busy = previewMutation.isPending || executeMutation.isPending

  return <Modal open title="整理凭证号" width={760} style={{ top: 24 }}
    styles={{ body: { maxHeight: 'calc(100dvh - 180px)', overflowY: 'auto' } }}
    onCancel={onCancel} closable={!busy} maskClosable={!busy}
    keyboard={!busy} footer={[
      <Button key="cancel" disabled={busy} onClick={onCancel}>关闭</Button>,
      <Button key="preview" loading={previewMutation.isPending} disabled={busy || period?.status !== 'OPEN' || !validNumber}
        onClick={() => previewMutation.mutate()}>预览整理</Button>,
      <Button key="execute" type="primary" loading={executeMutation.isPending}
        disabled={busy || !preview || preview.changedCount === 0} onClick={() => executeMutation.mutate()}>确认整理</Button>,
    ]}>
    <Space direction="vertical" size="middle" style={{ width: '100%' }}>
      <Typography.Text type="secondary">整理所选期间的全部未删除凭证，包括自动凭证，不受当前列表筛选和分页影响。</Typography.Text>
      <Form layout="vertical" disabled={busy}>
        <Space wrap align="start">
          <Form.Item label="会计期间" required>
            <Select aria-label="会计期间" placeholder="请选择一个会计期间" value={periodId} style={{ minWidth: 220 }}
              options={periods.map((item) => ({ value: item.id, label: `${item.periodCode}${item.status !== 'OPEN' ? '（已结账）' : ''}`, disabled: item.status !== 'OPEN' }))}
              onChange={(value) => { setPeriodId(value); invalidatePreview() }} />
          </Form.Item>
          <Form.Item label="凭证字"><Input aria-label="凭证字" value="记" readOnly style={{ width: 70 }} /></Form.Item>
          <Form.Item label="起始凭证号" required validateStatus={!validNumber ? 'error' : undefined}
            help={!validNumber ? '请输入正整数' : undefined}>
            <Input aria-label="起始凭证号" inputMode="numeric" value={startNumber} style={{ width: 160 }}
              onChange={(event) => { setStartNumber(event.target.value); invalidatePreview() }} />
          </Form.Item>
        </Space>
        <Form.Item label="整理方式">
          <Radio.Group value={mode} onChange={(event) => { setMode(event.target.value); invalidatePreview() }}>
            <Space direction="vertical">
              <Radio value="DATE_ORDER">按凭证日期重新编号，并补齐缺号</Radio>
              <Radio value="NUMBER_ORDER">按原凭证号顺序补齐缺号</Radio>
            </Space>
          </Radio.Group>
        </Form.Item>
        <Typography.Text type="secondary">两种方式都从起始号连续编号。按日期整理时，同一天按原凭证号排序。</Typography.Text>
      </Form>
      {period && period.status !== 'OPEN' && <Alert type="info" showIcon message="已结账期间不能整理凭证号，请先反结账。" />}
      {error && <Alert type="error" showIcon message={error} />}
      {preview && (preview.changedCount === 0
        ? <Alert type="success" showIcon message="当前期间无需整理凭证号" description={`共 ${preview.totalCount} 张凭证，编号已符合所选规则。`} />
        : <>
          <Alert type="info" showIcon message={`共 ${preview.totalCount} 张凭证，需修改 ${preview.changedCount} 张`}
            description={`整理后编号：记-${preview.firstNumber} 至 记-${preview.lastNumber}。确认后更新字号，分录和金额保持不变。`} />
          <Table size="small" rowKey="voucherId" dataSource={preview.changes} scroll={{ x: 580 }}
            pagination={{ pageSize: 8, showSizeChanger: false, hideOnSinglePage: true }}
            columns={[
              { title: '凭证日期', dataIndex: 'voucherDate', width: 115 },
              { title: '摘要', dataIndex: 'summary', render: (value: string | null) => value || '—' },
              { title: '原字号', dataIndex: 'oldNumber', width: 110, render: (value: string) => `记-${value}` },
              { title: '新字号', dataIndex: 'newNumber', width: 110, render: (value: string) => <Typography.Text strong>{`记-${value}`}</Typography.Text> },
            ]} />
        </>)}
    </Space>
  </Modal>
}
