import { App as AntApp, Avatar, Button, Layout, Menu, Select, Space, Spin, Typography } from 'antd'
import { BankOutlined, BookOutlined, FileSearchOutlined, FileTextOutlined, LogoutOutlined, SettingOutlined, HddOutlined, TeamOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Outlet, useLocation, useNavigate, useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Ledger, User } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { logoutOidc, isOidcConfigured } from '../auth/session'

const { Header, Sider, Content } = Layout

export function AppShell() {
  const { session, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const { ledgerId } = useParams()
  const ledgers = useQuery({
    queryKey: ['ledgers'],
    queryFn: () => apiFetch<Ledger[]>('/ledgers', session!),
    enabled: Boolean(session),
  })
  const me = useQuery({
    queryKey: ['me'],
    queryFn: () => apiFetch<User>('/me', session!),
    enabled: Boolean(session),
  })

  const selectedKey = location.pathname.startsWith('/admin')
    ? 'admin'
    : location.pathname.includes('/vouchers')
      ? 'vouchers'
    : location.pathname.includes('/fixed-assets')
      ? 'fixed-assets'
    : location.pathname.includes('/reports')
      ? 'reports'
      : location.pathname.includes('/documents')
        ? 'documents'
        : location.pathname.includes('/settings')
          ? 'settings'
          : location.pathname.includes('/audit')
            ? 'audit'
            : 'overview'

  const handleLogout = async () => {
    signOut()
    if (isOidcConfigured()) {
      await logoutOidc().catch(() => navigate('/login', { replace: true }))
    } else {
      navigate('/login', { replace: true })
    }
  }

  return (
    <AntApp>
      <Layout style={{ minHeight: '100vh' }}>
        <Sider breakpoint="lg" collapsedWidth="0" theme="light" width={224}>
          <div style={{ padding: '20px 20px 14px', borderBottom: '1px solid #e5e7eb' }}>
            <Typography.Title level={4} style={{ margin: 0, color: '#153b5b' }}>AI 财务系统</Typography.Title>
            <Typography.Text type="secondary">财务工作台</Typography.Text>
          </div>
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            items={[
              { key: 'overview', icon: <BankOutlined />, label: '账套概览', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/overview`) },
              { key: 'vouchers', icon: <BookOutlined />, label: '凭证', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/vouchers`) },
              { key: 'fixed-assets', icon: <HddOutlined />, label: '固定资产', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/fixed-assets`) },
              { key: 'reports', icon: <FileTextOutlined />, label: '账簿与报表', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/reports/trial-balance`) },
              { key: 'documents', icon: <FileSearchOutlined />, label: '附件与提取', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/documents`) },
              { key: 'settings', icon: <SettingOutlined />, label: '账套设置', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/settings/periods`) },
              { key: 'audit', icon: <FileSearchOutlined />, label: '审计日志', onClick: () => ledgerId && navigate(`/ledgers/${ledgerId}/audit`) },
              ...(me.data?.displayName?.toLowerCase() === 'admin'
                ? [{ key: 'admin', icon: <TeamOutlined />, label: '平台管理', onClick: () => navigate('/admin') }]
                : []),
            ]}
          />
        </Sider>
        <Layout>
          <Header style={{ height: 56, padding: '0 24px', background: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'space-between', borderBottom: '1px solid #e5e7eb' }}>
            <Select
              aria-label="选择账套"
              placeholder="选择账套"
              style={{ minWidth: 240 }}
              loading={ledgers.isLoading}
              value={ledgerId}
              options={(ledgers.data || []).map((ledger) => ({ label: ledger.name, value: ledger.id }))}
              onChange={(id) => navigate(`/ledgers/${id}/overview`)}
            />
            <Space>
              {me.isLoading ? <Spin size="small" /> : <Avatar style={{ backgroundColor: '#153b5b' }}>{(me.data?.displayName || '用').slice(0, 1)}</Avatar>}
              <Typography.Text>{me.data?.displayName || '当前用户'}</Typography.Text>
              <Button type="text" icon={<LogoutOutlined />} onClick={handleLogout}>退出</Button>
            </Space>
          </Header>
          <Content className="app-content"><Outlet /></Content>
        </Layout>
      </Layout>
    </AntApp>
  )
}
