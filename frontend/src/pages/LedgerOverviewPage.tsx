import { Alert, Card, Col, Row, Space, Statistic, Tag, Typography } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { Link, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Account, Ledger, Period, Voucher } from '../api/types'
import { useAuth } from '../auth/AuthProvider'

export function LedgerOverviewPage() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const ledger = useQuery({ queryKey: ['ledger', ledgerId], queryFn: () => apiFetch<Ledger>(`/ledgers/${ledgerId}`, session!), enabled: Boolean(session && ledgerId) })
  const periods = useQuery({ queryKey: ['periods', ledgerId], queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!), enabled: Boolean(session && ledgerId) })
  const accounts = useQuery({ queryKey: ['accounts', ledgerId], queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!), enabled: Boolean(session && ledgerId) })
  const vouchers = useQuery({ queryKey: ['vouchers', ledgerId, 'overview'], queryFn: () => apiFetch<Voucher[]>(`/ledgers/${ledgerId}/vouchers?limit=5&offset=0`, session!), enabled: Boolean(session && ledgerId) })

  if (ledger.isError) return <Alert type="error" showIcon message="账套读取失败" description="请检查权限或重试。" />
  const currentPeriod = periods.data?.find((period) => period.status === 'OPEN')
  return <Space direction="vertical" size={16} style={{ width: '100%' }}>
    <div className="page-heading"><div><Typography.Title level={1}>账套概览</Typography.Title><Typography.Text type="secondary">查看当前账套的基础状态和最近业务。</Typography.Text></div><Tag color={ledger.data?.status === 'ACTIVE' ? 'green' : 'default'}>{ledger.data?.status || '加载中'}</Tag></div>
    <Row gutter={[16, 16]}>
      <Col xs={24} sm={8}><Card><Statistic title="当前期间" value={currentPeriod?.periodCode || '未开启'} /></Card></Col>
      <Col xs={24} sm={8}><Card><Statistic title="科目数量" value={accounts.data?.length || 0} /></Card></Col>
      <Col xs={24} sm={8}><Card><Statistic title="凭证数量（最近页）" value={vouchers.data?.length || 0} /></Card></Col>
    </Row>
    <Card title="常用操作">
      <Space wrap>
        <Link to={`/ledgers/${ledgerId}/vouchers/new`}>新建凭证</Link>
        <Link to={`/ledgers/${ledgerId}/reports/trial-balance`}>查看科目余额表</Link>
        <Link to={`/ledgers/${ledgerId}/documents`}>管理附件</Link>
        <Link to={`/ledgers/${ledgerId}/settings/periods`}>账套设置</Link>
      </Space>
    </Card>
  </Space>
}
