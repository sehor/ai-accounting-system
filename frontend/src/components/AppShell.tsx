import {
  App as AntApp, Avatar, Button, Dropdown, Layout, Menu, Select, Space, Spin, Tabs, Typography,
} from 'antd'
import {
  BankOutlined, BookOutlined, DownOutlined, FileSearchOutlined, FileTextOutlined,
  HddOutlined, HomeOutlined, LogoutOutlined, MenuOutlined, PlusSquareOutlined, SettingOutlined, TeamOutlined,
} from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { useLocation, useNavigate, useOutlet } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Ledger, User } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { logoutOidc, isOidcConfigured } from '../auth/session'
import { clearWorkspaceTabDirty, isWorkspaceTabDirty } from './workspaceDirty'

const { Header, Sider, Content } = Layout
const TAB_STORAGE_PREFIX = 'ai-accounting.tabs.'

type WorkspaceTab = {
  id: string
  title: string
  location: string
  closable: boolean
}

function ledgerIdFromPath(pathname: string): string | undefined {
  return pathname.match(/^\/ledgers\/([^/]+)\//)?.[1]
}

export function describeTab(pathname: string, search: string): WorkspaceTab | undefined {
  const ledgerId = ledgerIdFromPath(pathname)
  if (!ledgerId) return undefined
  const base = `/ledgers/${ledgerId}`
  const location = `${pathname}${search}`
  if (pathname === `${base}/overview`) return { id: 'home', title: '首页', location, closable: false }
  if (pathname === `${base}/vouchers`) return { id: 'vouchers', title: '查凭证', location, closable: true }
  if (pathname === `${base}/vouchers/new`) return { id: 'voucher-new', title: '新增凭证', location, closable: true }
  const voucherId = pathname.match(/\/vouchers\/([^/]+)$/)?.[1]
  if (voucherId) return { id: `voucher-${voucherId}`, title: `凭证 ${voucherId.slice(0, 6)}`, location, closable: true }
  const bookType = pathname.match(/\/books\/([^/]+)$/)?.[1]
  if (bookType) return {
    id: `book-${bookType}`,
    title: { 'trial-balance': '科目余额表', 'general-ledger': '总账', 'sub-ledger': '明细账' }[bookType] || '账簿',
    location,
    closable: true,
  }
  const reportType = pathname.match(/\/reports\/([^/]+)$/)?.[1]
  if (reportType) return {
    id: `report-${reportType}`,
    title: { 'balance-sheet': '资产负债表', 'income-statement': '利润表', 'trial-balance': '科目余额表' }[reportType] || '报表',
    location,
    closable: true,
  }
  if (pathname.includes('/fixed-assets')) return { id: 'fixed-assets', title: '固定资产', location, closable: true }
  if (pathname.includes('/documents')) return { id: 'documents', title: '附件与提取', location, closable: true }
  if (pathname.includes('/settings')) return { id: 'settings', title: '账套设置', location, closable: true }
  if (pathname.includes('/audit')) return { id: 'audit', title: '审计日志', location, closable: true }
  return { id: pathname, title: '工作页', location, closable: true }
}

function readTabs(storageKey: string): WorkspaceTab[] {
  try {
    const value = JSON.parse(localStorage.getItem(storageKey) || '[]') as WorkspaceTab[]
    return Array.isArray(value) ? value.filter((tab) => tab.id && tab.location && tab.title) : []
  } catch {
    return []
  }
}

export function AppShell() {
  const { session, signOut } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const outlet = useOutlet()
  const ledgerId = ledgerIdFromPath(location.pathname)
  const userKey = session?.localUserId || session?.localUserName || 'oidc-user'
  const storageKey = ledgerId ? `${TAB_STORAGE_PREFIX}${userKey}.${ledgerId}` : ''
  const [tabs, setTabs] = useState<WorkspaceTab[]>([])
  const [tabsLedgerId, setTabsLedgerId] = useState<string>()
  const [menuCollapsed, setMenuCollapsed] = useState(false)
  const cache = useRef(new Map<string, ReactNode>())
  const currentTab = describeTab(location.pathname, location.search)
  const currentTabId = currentTab?.id
  const currentTabTitle = currentTab?.title
  const currentTabLocation = currentTab?.location
  const currentTabClosable = currentTab?.closable

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

  useEffect(() => {
    if (!ledgerId) {
      setTabs([])
      setTabsLedgerId(undefined)
      cache.current.clear()
      return
    }
    const home: WorkspaceTab = {
      id: 'home', title: '首页', location: `/ledgers/${ledgerId}/overview`, closable: false,
    }
    const restored = readTabs(`${TAB_STORAGE_PREFIX}${userKey}.${ledgerId}`)
      .filter((tab) => tab.location.startsWith(`/ledgers/${ledgerId}/`))
    const merged = [home, ...restored.filter((tab) => tab.id !== 'home')]
    setTabs(merged)
    setTabsLedgerId(ledgerId)
    cache.current.clear()
  }, [ledgerId, userKey])

  useEffect(() => {
    if (!currentTabId || !currentTabTitle || !currentTabLocation || tabsLedgerId !== ledgerId) return
    const nextTab: WorkspaceTab = {
      id: currentTabId,
      title: currentTabTitle,
      location: currentTabLocation,
      closable: currentTabClosable ?? true,
    }
    setTabs((current) => {
      const existing = current.find((tab) => tab.id === currentTabId)
      if (existing?.location === currentTabLocation && existing.title === currentTabTitle) return current
      return existing
        ? current.map((tab) => tab.id === currentTabId ? nextTab : tab)
        : [...current, nextTab]
    })
  }, [currentTabClosable, currentTabId, currentTabLocation, currentTabTitle, ledgerId, tabsLedgerId])

  useEffect(() => {
    if (storageKey && tabsLedgerId === ledgerId && tabs.length) {
      localStorage.setItem(storageKey, JSON.stringify(tabs))
    }
  }, [ledgerId, storageKey, tabs, tabsLedgerId])

  if (currentTab) cache.current.set(currentTab.id, outlet)

  const selectedKey = useMemo(() => {
    const path = location.pathname
    if (path.includes('/vouchers/new')) return 'voucher-new'
    if (path.includes('/vouchers')) return 'vouchers'
    if (path.includes('/books/trial-balance')) return 'trial-balance'
    if (path.includes('/books/general-ledger')) return 'general-ledger'
    if (path.includes('/books/sub-ledger')) return 'sub-ledger'
    if (path.includes('/reports/balance-sheet')) return 'balance-sheet'
    if (path.includes('/reports/income-statement')) return 'income-statement'
    if (path.includes('/fixed-assets')) return 'fixed-assets'
    if (path.includes('/documents')) return 'documents'
    if (path.includes('/settings')) return 'settings'
    if (path.includes('/audit')) return 'audit'
    if (path.startsWith('/admin')) return 'admin'
    return 'overview'
  }, [location.pathname])

  const go = (suffix: string) => ledgerId && navigate(`/ledgers/${ledgerId}/${suffix}`)
  const removeTab = (tabId: string) => {
    const target = tabs.find((tab) => tab.id === tabId)
    if (!target?.closable) return
    if (isWorkspaceTabDirty(tabId)
      && !window.confirm('关闭凭证标签？未保存的修改将丢失。')) return
    const index = tabs.findIndex((tab) => tab.id === tabId)
    const remaining = tabs.filter((tab) => tab.id !== tabId)
    cache.current.delete(tabId)
    clearWorkspaceTabDirty(tabId)
    setTabs(remaining)
    if (currentTab?.id === tabId) navigate((remaining[index] || remaining[index - 1] || remaining[0]).location)
  }
  const closeOthers = () => {
    if (!currentTab) return
    const closing = tabs.filter((tab) => tab.closable && tab.id !== currentTab.id)
    if (closing.some((tab) => isWorkspaceTabDirty(tab.id))
      && !window.confirm('关闭其他标签？未保存的凭证修改将丢失。')) return
    closing.forEach((tab) => { cache.current.delete(tab.id); clearWorkspaceTabDirty(tab.id) })
    setTabs((current) => current.filter((tab) => !tab.closable || tab.id === currentTab.id))
  }
  const closeAll = () => {
    const closing = tabs.filter((tab) => tab.closable)
    if (closing.some((tab) => isWorkspaceTabDirty(tab.id))
      && !window.confirm('关闭全部标签？未保存的凭证修改将丢失。')) return
    closing.forEach((tab) => { cache.current.delete(tab.id); clearWorkspaceTabDirty(tab.id) })
    setTabs((current) => current.filter((tab) => !tab.closable))
    if (ledgerId) navigate(`/ledgers/${ledgerId}/overview`)
  }
  const handleLogout = async () => {
    Object.keys(localStorage).filter((key) => key.startsWith(`${TAB_STORAGE_PREFIX}${userKey}.`))
      .forEach((key) => localStorage.removeItem(key))
    signOut()
    if (isOidcConfigured()) await logoutOidc().catch(() => navigate('/login', { replace: true }))
    else navigate('/login', { replace: true })
  }

  return <AntApp>
    <a className="skip-link" href="#workspace-content">跳到主要内容</a>
    <Layout className="app-shell">
      <Sider breakpoint="lg" collapsedWidth="0" collapsed={menuCollapsed} onCollapse={setMenuCollapsed}
        trigger={null}
        theme="dark" width={96} className="main-sider">
        <div className="brand-mark"><BookOutlined /><span>AI 财务</span></div>
        <Menu mode="vertical" theme="dark" selectedKeys={[selectedKey]} items={[
          { key: 'overview', icon: <HomeOutlined />, label: '首页', onClick: () => go('overview') },
          { key: 'vouchers-group', icon: <BookOutlined />, label: '凭证', children: [
            { key: 'voucher-new', icon: <PlusSquareOutlined />, label: '录凭证', onClick: () => go('vouchers/new') },
            { key: 'vouchers', icon: <FileSearchOutlined />, label: '查凭证', onClick: () => go('vouchers') },
          ] },
          { key: 'books-group', icon: <BankOutlined />, label: '账簿', children: [
            { key: 'trial-balance', label: '科目余额表', onClick: () => go('books/trial-balance') },
            { key: 'general-ledger', label: '总账', onClick: () => go('books/general-ledger') },
            { key: 'sub-ledger', label: '明细账', onClick: () => go('books/sub-ledger') },
          ] },
          { key: 'reports-group', icon: <FileTextOutlined />, label: '报表', children: [
            { key: 'balance-sheet', label: '资产负债表', onClick: () => go('reports/balance-sheet') },
            { key: 'income-statement', label: '利润表', onClick: () => go('reports/income-statement') },
          ] },
          { key: 'fixed-assets', icon: <HddOutlined />, label: '固定资产', onClick: () => go('fixed-assets') },
          { key: 'documents', icon: <FileSearchOutlined />, label: '附件', onClick: () => go('documents') },
          { key: 'settings', icon: <SettingOutlined />, label: '设置', onClick: () => go('settings/periods') },
          { key: 'audit', icon: <FileSearchOutlined />, label: '审计', onClick: () => go('audit') },
          ...(me.data?.displayName?.toLowerCase() === 'admin'
            ? [{ key: 'admin', icon: <TeamOutlined />, label: '平台', onClick: () => navigate('/admin') }]
            : []),
        ]} />
      </Sider>
      <Button type="primary" className="mobile-menu-trigger"
        style={{ left: menuCollapsed ? 0 : 96 }}
        aria-label={menuCollapsed ? '展开主菜单' : '收起主菜单'}
        icon={<MenuOutlined />} onClick={() => setMenuCollapsed((collapsed) => !collapsed)} />
      <Layout>
        <Header className="app-header">
          <Select aria-label="选择账套" placeholder="选择账套" className="ledger-select" loading={ledgers.isLoading}
            value={ledgerId} options={(ledgers.data || []).map((ledger) => ({ label: ledger.name, value: ledger.id }))}
            onChange={(id) => navigate(`/ledgers/${id}/overview`)} />
          <Space>
            {me.isLoading ? <Spin size="small" /> : <Avatar>{(me.data?.displayName || '用').slice(0, 1)}</Avatar>}
            <Typography.Text className="current-user">{me.data?.displayName || '当前用户'}</Typography.Text>
            <Button type="text" icon={<LogoutOutlined />} onClick={handleLogout}>退出</Button>
          </Space>
        </Header>
        {ledgerId && <div className="workspace-tabs-bar">
          <Tabs type="editable-card" hideAdd activeKey={currentTab?.id} onChange={(key) => {
            const tab = tabs.find((item) => item.id === key)
            if (tab) navigate(tab.location)
          }} onEdit={(targetKey, action) => action === 'remove' && removeTab(String(targetKey))}
            items={tabs.map((tab) => ({ key: tab.id, label: tab.title, closable: tab.closable }))} />
          <Dropdown menu={{ items: [
            { key: 'others', label: '关闭其他标签', onClick: closeOthers },
            { key: 'all', label: '关闭全部标签', onClick: closeAll },
          ] }}><Button aria-label="标签关闭选项" icon={<DownOutlined />} /></Dropdown>
        </div>}
        <Content id="workspace-content" className="app-content">
          {!currentTab ? outlet : tabs.map((tab) => <div key={tab.id} hidden={tab.id !== currentTab.id}
            aria-hidden={tab.id !== currentTab.id} className="workspace-panel">
            {cache.current.get(tab.id)}
          </div>)}
        </Content>
      </Layout>
    </Layout>
  </AntApp>
}
