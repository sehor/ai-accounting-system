import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { ConfigProvider } from 'antd'
import { BrowserRouter, Navigate, Route, Routes } from 'react-router-dom'
import { AppShell } from '../components/AppShell'
import { AuthProvider, useAuth } from '../auth/AuthProvider'
import { AuthCallbackPage } from '../pages/AuthCallbackPage'
import { AuditPage } from '../pages/AuditPage'
import { DocumentsPage } from '../pages/DocumentsPage'
import { LedgerListPage } from '../pages/LedgerListPage'
import { LedgerOverviewPage } from '../pages/LedgerOverviewPage'
import { LoginPage } from '../pages/LoginPage'
import { ReportsPage } from '../pages/ReportsPage'
import { SettingsPage } from '../pages/SettingsPage'
import { VoucherEditorPage, VoucherListPage } from '../pages/VoucherPages'
import { FixedAssetEditorPage, FixedAssetListPage } from '../pages/FixedAssetPages'
import { AdminPage } from '../pages/AdminPage'

const queryClient = new QueryClient({ defaultOptions: { queries: { staleTime: 15_000, retry: 1 } } })

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { session } = useAuth()
  return session ? <>{children}</> : <Navigate to="/login" replace />
}

function AppRoutes() {
  return <Routes>
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
      <Route path="ledgers/:ledgerId/reports/:reportType" element={<ReportsPage />} />
      <Route path="ledgers/:ledgerId/documents/*" element={<DocumentsPage />} />
      <Route path="ledgers/:ledgerId/settings/*" element={<SettingsPage />} />
      <Route path="ledgers/:ledgerId/audit" element={<AuditPage />} />
    </Route>
    <Route path="*" element={<Navigate to="/" replace />} />
  </Routes>
}

export function App() {
  return <QueryClientProvider client={queryClient}>
    <ConfigProvider theme={{ token: { colorPrimary: '#153b5b', colorInfo: '#153b5b', colorTextSecondary: '#595959', borderRadius: 4 }, components: { Table: { cellPaddingBlock: 10 }, Layout: { siderBg: '#fff' } } }}>
      <BrowserRouter><AuthProvider><AppRoutes /></AuthProvider></BrowserRouter>
    </ConfigProvider>
  </QueryClientProvider>
}
