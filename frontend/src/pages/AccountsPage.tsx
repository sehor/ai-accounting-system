import { Tabs, Typography } from 'antd'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { useParams } from 'react-router-dom'
import { apiFetch } from '../api/client'
import type { Account, DimensionType, LedgerRole, Period } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'
import { AccountsTab, ACCOUNT_CATEGORY_LABELS, type AccountCategoryTab } from './AccountsTab'

const categories: Array<{ key: AccountCategoryTab; label: string }> = [
  { key: 'CURRENT_ASSET', label: ACCOUNT_CATEGORY_LABELS.CURRENT_ASSET },
  { key: 'NON_CURRENT_ASSET', label: ACCOUNT_CATEGORY_LABELS.NON_CURRENT_ASSET },
  { key: 'CURRENT_LIABILITY', label: ACCOUNT_CATEGORY_LABELS.CURRENT_LIABILITY },
  { key: 'NON_CURRENT_LIABILITY', label: ACCOUNT_CATEGORY_LABELS.NON_CURRENT_LIABILITY },
  { key: 'EQUITY', label: ACCOUNT_CATEGORY_LABELS.EQUITY },
  { key: 'COST', label: ACCOUNT_CATEGORY_LABELS.COST },
  { key: 'OPERATING_REVENUE', label: ACCOUNT_CATEGORY_LABELS.OPERATING_REVENUE },
  { key: 'OTHER_INCOME', label: ACCOUNT_CATEGORY_LABELS.OTHER_INCOME },
  { key: 'OPERATING_COST_AND_TAX', label: ACCOUNT_CATEGORY_LABELS.OPERATING_COST_AND_TAX },
  { key: 'OTHER_EXPENSE', label: ACCOUNT_CATEGORY_LABELS.OTHER_EXPENSE },
  { key: 'PERIOD_EXPENSE', label: ACCOUNT_CATEGORY_LABELS.PERIOD_EXPENSE },
  { key: 'INCOME_TAX', label: ACCOUNT_CATEGORY_LABELS.INCOME_TAX },
  { key: 'PRIOR_YEAR_ADJUSTMENT', label: ACCOUNT_CATEGORY_LABELS.PRIOR_YEAR_ADJUSTMENT },
]

function categoryFromSearch(value: string | null): AccountCategoryTab {
  return categories.some((category) => category.key === value) ? value as AccountCategoryTab : 'CURRENT_ASSET'
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
  const periods = useQuery({
    queryKey: ['periods', ledgerId],
    queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!),
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
    <AccountsTab ledgerId={ledgerId} session={session!}
      accounts={accounts.data || []} dimensionTypes={dimensionTypes.data || []} periods={periods.data || []}
      loading={accounts.isLoading} writable={['OWNER', 'EDITOR'].includes(ledgerRole.data?.role || '')}
      category={category} onChanged={() => void client.invalidateQueries({ queryKey: ['accounts', ledgerId] })} />
  </section>
}
