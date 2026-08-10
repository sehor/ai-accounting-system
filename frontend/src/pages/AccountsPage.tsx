import { Tabs, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Account, DimensionType, LedgerRole } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'
import { AccountsTab, type AccountCategoryTab } from './AccountsTab'

const categories: Array<{ key: AccountCategoryTab; label: string }> = [
  { key: 'ASSET', label: '资产' },
  { key: 'LIABILITY', label: '负债' },
  { key: 'EQUITY', label: '权益' },
  { key: 'COST', label: '成本' },
  { key: 'PROFIT_LOSS', label: '损益' },
]

function categoryFromSearch(value: string | null): AccountCategoryTab {
  return categories.some((category) => category.key === value) ? value as AccountCategoryTab : 'ASSET'
}

export function AccountsPage() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const client = useQueryClient()
  const [search, setSearch] = useWorkspaceSearchParams()
  const category = categoryFromSearch(search.get('category'))
  useEffect(() => {
    if (search.get('category')) return
    const nextSearch = new URLSearchParams(search)
    nextSearch.set('category', category)
    setSearch(nextSearch, { replace: true })
  }, [category, search, setSearch])
  const accounts = useQuery({
    queryKey: ['accounts', ledgerId],
    queryFn: () => apiFetch<Account[]>(`/ledgers/${ledgerId}/accounts`, session!),
    enabled: Boolean(session && ledgerId),
  })
  const dimensionTypes = useQuery({
    queryKey: ['dimension-types', ledgerId],
    queryFn: () => apiFetch<DimensionType[]>(`/ledgers/${ledgerId}/dimension-types`, session!),
    enabled: Boolean(session && ledgerId),
  })
  const ledgerRole = useQuery({
    queryKey: ['ledger-role', ledgerId],
    queryFn: () => apiFetch<{ role: LedgerRole }>(`/ledgers/${ledgerId}/role`, session!),
    enabled: Boolean(session && ledgerId),
  })

  const changeCategory = (next: string) => {
    const nextSearch = new URLSearchParams(search)
    nextSearch.set('category', next)
    setSearch(nextSearch)
  }

  return <section className="accounts-page">
    <Typography.Title level={1}>科目</Typography.Title>
    <Tabs className="account-category-tabs" activeKey={category} onChange={changeCategory}
      items={categories.map((item) => ({ key: item.key, label: item.label }))} />
    <AccountsTab key={`${ledgerId}:${category}`} ledgerId={ledgerId} session={session!}
      accounts={accounts.data || []} dimensionTypes={dimensionTypes.data || []}
      loading={accounts.isLoading} writable={['OWNER', 'EDITOR'].includes(ledgerRole.data?.role || '')}
      category={category} onChanged={() => void client.invalidateQueries({ queryKey: ['accounts', ledgerId] })} />
  </section>
}
