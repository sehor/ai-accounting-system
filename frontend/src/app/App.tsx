import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider, Space, Spin, Typography } from 'antd'
import { lazy, Suspense } from 'react'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { AuthProvider, useAuth } from '../auth/AuthProvider'

const AuthCallbackPage = lazy(() => import('../pages/AuthCallbackPage').then((module) => ({ default: module.AuthCallbackPage })))
const AuditPage = lazy(() => import('../pages/AuditPage').then((module) => ({ default: module.AuditPage })))
const DocumentsPage = lazy(() => import('../pages/DocumentsPage').then((module) => ({ default: module.DocumentsPage })))
const LedgerListPage = lazy(() => import('../pages/LedgerListPage').then((module) => ({ default: module.LedgerListPage })))
const LedgerOverviewPage = lazy(() => import('../pages/LedgerOverviewPage').then((module) => ({ default: module.LedgerOverviewPage })))
const LoginPage = lazy(() => import('../pages/LoginPage').then((module) => ({ default: module.LoginPage })))
const ReportsPage = lazy(() => import('../pages/ReportsPage').then((module) => ({ default: module.ReportsPage })))
const SettingsPage = lazy(() => import('../pages/SettingsPage').then((module) => ({ default: module.SettingsPage })))
const VoucherEditorPage = lazy(() => import('../pages/VoucherPages').then((module) => ({ default: module.VoucherEditorPage })))
const VoucherListPage = lazy(() => import('../pages/VoucherListPage').then((module) => ({ default: module.VoucherListPage })))
const FixedAssetEditorPage = lazy(() => import('../pages/FixedAssetPages').then((module) => ({ default: module.FixedAssetEditorPage })))
const FixedAssetListPage = lazy(() => import('../pages/FixedAssetListPage').then((module) => ({ default: module.FixedAssetListPage })))
const AdminPage = lazy(() => import('../pages/AdminPage').then((module) => ({ default: module.AdminPage })))
const BooksPage = lazy(() => import('../pages/BooksPage').then((module) => ({ default: module.BooksPage })))
const AccountsPage = lazy(() => import('../pages/AccountsPage').then((module) => ({ default: module.AccountsPage })))

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 15_000, retry: 1 } } })

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session } = useAuth()
  return session ? <>{children}</> : <Navigate to="/login" replace />
}

function AppRoutes() {
  return <Suspense fallback={<Space role="status"><Spin /><Typography.Text>页面加载中</Typography.Text></Space>}><Routes>
    <Route path="/login" element={<LoginPage />} />
    <Route path="/auth/callback" element={<AuthCallbackPage />} />
    <Route path="/" element={<RequireAuth><AppShell /></RequireAuth>}>
      <Route index element={<Navigate to="/ledgers" replace />} />
      <Route path="ledgers" element={<LedgerListPage />} />
      <Route path="admin" element={<AdminPage />} />
      <Route path="ledgers/:ledgerId/overview" element={<LedgerOverviewPage />} />
      <Route path="ledgers/:ledgerId/vouchers" element={<VoucherListPage />} />
      <Route path="ledgers/:ledgerId/vouchers/:voucherId" element={<VoucherEditorPage />} />
      <Route path="ledgers/:ledgerId/fixed-assets" element={<FixedAssetListPage />} />
      <Route path="ledgers/:ledgerId/fixed-assets/new" element={<FixedAssetEditorPage />} />
      <Route path="ledgers/:ledgerId/fixed-assets/:assetId" element={<FixedAssetEditorPage />} />
      <Route path="ledgers/:ledgerId/books/:bookType" element={<BooksPage />} />
      <Route path="ledgers/:ledgerId/accounts" element={<AccountsPage />} />
      <Route path="ledgers/:ledgerId/reports/:reportType" element={<ReportsPage />} />
      <Route path="ledgers/:ledgerId/documents/*" element={<DocumentsPage />} />
      <Route path="ledgers/:ledgerId/settings/*" element={<SettingsPage />} />
      <Route path="ledgers/:ledgerId/audit" element={<AuditPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes></Suspense>
}

export function App() {
  return <QueryClientProvider client={queryClient}>
    <ConfigProvider theme={{ token: { colorPrimary: '#1598d4', colorInfo: '#1598d4', colorTextSecondary: '#595959', borderRadius: 2, fontSize: 13 }, components: { Table: { cellPaddingBlock: 7, cellPaddingInline: 8 }, Layout: { siderBg: '#17658a', headerBg: '#fff' }, Menu: { darkItemBg: '#17658a', darkItemSelectedBg: '#0f83b8' } } }}>
      <BrowserRouter><AuthProvider><AppRoutes /></AuthProvider></BrowserRouter>
    </ConfigProvider>
  </QueryClientProvider>
}
