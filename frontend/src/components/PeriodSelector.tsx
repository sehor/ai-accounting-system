import { ReloadOutlined } from '@ant-design/icons'
import { useQuery } from '@tanstack/react-query'
import { Button, Select, Space, Typography } from 'antd'
import dayjs from 'dayjs'
import { useEffect, useMemo } from 'react'
import { apiFetch } from '../api/client'
import type { Period } from '../api/types'
import { useAuth } from '../auth/AuthProvider'
import { useWorkspaceSearchParams } from './workspaceSearch'

export function selectDefaultPeriod(periods: Period[], currentMonth = dayjs().format('YYYY-MM')): string | undefined {
  const withVouchers = periods.filter((period) => period.hasVouchers)
  return withVouchers.at(-1)?.periodCode
    || periods.find((period) => period.periodCode === currentMonth)?.periodCode
    || periods.at(-1)?.periodCode
}

export function usePeriodFilter(ledgerId: string) {
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const periods = useQuery({
    queryKey: ['periods', ledgerId],
    queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!),
    enabled: Boolean(session && ledgerId),
  })
  const requested = search.get('periodCode') || undefined
  const hasDateRange = Boolean(search.get('startDate') || search.get('endDate'))
  const periodCode = useMemo(() => {
    if (hasDateRange) return undefined
    if (requested && periods.data?.some((period) => period.periodCode === requested)) return requested
    return selectDefaultPeriod(periods.data || [])
  }, [hasDateRange, periods.data, requested])

  useEffect(() => {
    if (!periodCode || requested === periodCode) return
    const next = new URLSearchParams(search)
    next.set('periodCode', periodCode)
    next.delete('offset')
    next.delete('page')
    setSearch(next, { replace: true })
  }, [periodCode, requested, search, setSearch])

  const setPeriodCode = (value: string) => {
    const next = new URLSearchParams(search)
    next.set('periodCode', value)
    next.delete('startDate')
    next.delete('endDate')
    next.delete('offset')
    next.delete('page')
    setSearch(next)
  }

  return { periods, periodCode, setPeriodCode }
}

export function PeriodSelector({
  label = '会计期间', periodCode, periods, loading, refreshing, onChange, onRefresh,
}: {
  label?: string
  periodCode?: string
  periods: Period[]
  loading?: boolean
  refreshing?: boolean
  onChange: (periodCode: string) => void
  onRefresh: () => void
}) {
  return <Space className="period-selector" size={8} wrap>
    <Typography.Text>{label}</Typography.Text>
    <Select
      aria-label={label}
      value={periodCode}
      loading={loading}
      placeholder="选择期间"
      options={periods.map((period) => ({
        value: period.periodCode,
        label: `${period.periodCode.slice(0, 4)}年第${Number(period.periodCode.slice(5))}期`,
      }))}
      onChange={onChange}
    />
    <Button aria-label="刷新当前期间数据" icon={<ReloadOutlined />} loading={refreshing} onClick={onRefresh} />
  </Space>
}
