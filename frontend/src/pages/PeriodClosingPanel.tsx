import { Alert, Button, Card, Form, Select, Space, Tag, Typography, message } from 'antd'
import { SettingOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useRef, useState } from 'react'
import { apiFetch, ApiError, jsonBody, type ApiAuth } from '../api/client'
import type { Account, Period, PeriodClosingSettings, PeriodClosingStatus, PeriodClosingStep, PeriodClosingStepType } from '../api/types'

const labels: Record<PeriodClosingStepType, string> = {
  DEPRECIATION: '计提固定资产折旧',
  EXPENSE_TRANSFER: '结转本期费用',
  REVENUE_TRANSFER: '结转本期收入',
  YEAR_END_PROFIT_TRANSFER: '结转本年利润',
}

export function PeriodClosingPanel({ ledgerId, session, period, accounts, onClose }: {
  ledgerId: string; session: ApiAuth; period: Period; accounts: Account[]; onClose: () => void
}) {
  const client = useQueryClient(); const [showSettings, setShowSettings] = useState(false); const [activeStep, setActiveStep] = useState<PeriodClosingStepType | null>(null); const generatingRef = useRef(false)
  const [messageApi, contextHolder] = message.useMessage()
  const status = useQuery({ queryKey: ['period-closing', ledgerId, period.id], queryFn: () => apiFetch<PeriodClosingStatus>(`/ledgers/${ledgerId}/period-closings/${period.id}`, session), retry: false })
  const settings = useQuery({ queryKey: ['period-closing-settings', ledgerId], queryFn: () => apiFetch<PeriodClosingSettings>(`/ledgers/${ledgerId}/period-closing-settings`, session), retry: false })
  const generate = useMutation({ mutationFn: (step: PeriodClosingStepType) => apiFetch<PeriodClosingStep>(`/ledgers/${ledgerId}/period-closings/${period.id}/steps/${step}:generate`, session, { method: 'POST' }), onSuccess: () => { void client.invalidateQueries({ queryKey: ['period-closing', ledgerId, period.id] }); void client.invalidateQueries({ queryKey: ['periods', ledgerId] }); void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] }) }, onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '生成结账凭证失败'), onSettled: () => { generatingRef.current = false; setActiveStep(null) } })
  const saveSettings = useMutation({ mutationFn: (value: { profitAccountId: string | null; retainedEarningsAccountId: string | null }) => apiFetch<PeriodClosingSettings>(`/ledgers/${ledgerId}/period-closing-settings`, session, { method: 'PATCH', body: jsonBody(value) }), onSuccess: () => { setShowSettings(false); void client.invalidateQueries({ queryKey: ['period-closing-settings', ledgerId] }); void client.invalidateQueries({ queryKey: ['period-closing', ledgerId, period.id] }) }, onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '科目配置保存失败') })
  const data = status.data
  const equityLeaves = accounts.filter((account) => account.category === 'EQUITY' && account.isLeaf && account.status === 'ACTIVE')
  const selected = settings.data
  const generateStep = (step: PeriodClosingStepType) => {
    if (generatingRef.current) return
    generatingRef.current = true
    setActiveStep(step)
    generate.mutate(step)
  }
  return <Space direction="vertical" style={{ width: '100%' }}>{contextHolder}
    {status.isLoading && <Typography.Text>正在读取结账状态…</Typography.Text>}
    {data && <>
      <Space wrap>
        <Typography.Text strong>期间：{period.periodCode}</Typography.Text>
        <Button icon={<SettingOutlined />} onClick={() => setShowSettings((value) => !value)}>结账科目</Button>
      </Space>
      {showSettings && selected && <Card size="small" title="结账科目配置"><Form layout="vertical" initialValues={{ profitAccountId: selected.profitAccountId || selected.defaultProfitAccountId, retainedEarningsAccountId: selected.retainedEarningsAccountId || selected.defaultRetainedEarningsAccountId }} onFinish={(value) => saveSettings.mutate(value)}><Form.Item name="profitAccountId" label="本年利润"><Select allowClear options={equityLeaves.map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item><Form.Item name="retainedEarningsAccountId" label="利润分配—未分配利润"><Select allowClear options={equityLeaves.map((account) => ({ value: account.id, label: `${account.code} ${account.name}` }))} /></Form.Item><Button type="primary" htmlType="submit" loading={saveSettings.isPending}>保存配置</Button></Form></Card>}
      <Space wrap style={{ width: '100%' }}>{data.steps.map((step) => <StepCard key={step.step} step={step} onGenerate={() => generateStep(step.step)} onVoucher={() => window.location.assign(`/ledgers/${ledgerId}/vouchers/${step.voucherId}`)} loading={activeStep === step.step && generate.isPending} disabled={generate.isPending} />)}</Space>
      {data.blockers.length > 0 && <Alert type="warning" showIcon message="存在结账阻塞" description={<ul>{data.blockers.map((blocker) => <li key={`${blocker.code}-${blocker.detail}`}>{blocker.title}：{blocker.detail}</li>)}</ul>} />}
      <Card size="small" title="试算平衡"><Space wrap><Typography.Text>期初差额：{data.trialBalance.openingDifference}</Typography.Text><Typography.Text>本期差额：{data.trialBalance.periodDifference}</Typography.Text><Typography.Text>期末差额：{data.trialBalance.closingDifference}</Typography.Text><Tag color={data.trialBalance.balanced ? 'success' : 'error'}>{data.trialBalance.balanced ? '平衡' : '不平衡'}</Tag></Space></Card>
      <Button type="primary" disabled={!data.canClose} onClick={onClose}>结账</Button>
    </>}
  </Space>
}

function StepCard({ step, onGenerate, onVoucher, loading, disabled }: { step: PeriodClosingStep; onGenerate: () => void; onVoucher: () => void; loading: boolean; disabled: boolean }) {
  const color = step.status === 'GENERATED' ? 'success' : step.status === 'STALE' || step.status === 'BLOCKED' ? 'error' : 'default'
  return <Card size="small" style={{ width: 280 }} title={labels[step.step]}><Space direction="vertical"><Space><Tag color={color}>{step.status}</Tag><Typography.Text>{step.amount}</Typography.Text></Space>{step.blockers.map((blocker) => <Typography.Text type="danger" key={blocker.code}>{blocker.detail}</Typography.Text>)}<Space>{step.voucherId && <Button size="small" disabled={disabled} onClick={onVoucher}>查看凭证</Button>}{step.status !== 'NOT_REQUIRED' && <Button size="small" type="primary" disabled={disabled} loading={loading} onClick={onGenerate}>{step.status === 'GENERATED' ? '重新生成' : '生成凭证'}</Button>}</Space></Space></Card>
}
