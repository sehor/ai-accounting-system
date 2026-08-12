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

export function usePeriodFilter(ledgerId: string, active = true) {
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const periods = useQuery({
    queryKey: ['periods', ledgerId],
    queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!),
    enabled: Boolean(active && session && ledgerId),
  })
  const requested = search.get('periodCode') || undefined
  const hasDateRange = Boolean(search.get('startDate') || search.get('endDate'))
  const periodCode = useMemo(() => {
    if (hasDateRange) return undefined
    if (requested && periods.data?.some((period) => period.periodCode === requested)) return requested
    return selectDefaultPeriod(periods.data || [])
  }, [hasDateRange, periods.data, requested])

  useEffect(() => {
    if (!active || !periodCode || requested === periodCode) return
    const next = new URLSearchParams(search)
    next.set('periodCode', periodCode)
    next.delete('offset')
    next.delete('page')
    setSearch(next, { replace: true })
  }, [active, periodCode, requested, search, setSearch])

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

export function usePeriodRangeFilter(ledgerId: string, active = true) {
  const { session } = useAuth()
  const [search, setSearch] = useWorkspaceSearchParams()
  const periods = useQuery({
    queryKey: ['periods', ledgerId],
    queryFn: () => apiFetch<Period[]>(`/ledgers/${ledgerId}/periods`, session!),
    enabled: Boolean(active && session && ledgerId),
  })
  const codes = useMemo(() => (periods.data || []).map((period) => period.periodCode), [periods.data])
  const legacy = search.get('periodCode') || undefined
  const requestedFrom = search.get('periodFrom') || legacy
  const requestedTo = search.get('periodTo') || legacy
  const fallback = selectDefaultPeriod(periods.data || [])
  const periodFrom = requestedFrom && codes.includes(requestedFrom) ? requestedFrom : fallback
  const validTo = requestedTo && codes.includes(requestedTo) ? requestedTo : periodFrom
  const periodTo = periodFrom && validTo && validTo >= periodFrom ? validTo : periodFrom

  useEffect(() => {
    if (!active || !periodFrom || !periodTo
      || (!legacy && search.get('periodFrom') === periodFrom && search.get('periodTo') === periodTo)) return
    const next = new URLSearchParams(search)
    next.delete('periodCode')
    next.set('periodFrom', periodFrom)
    next.set('periodTo', periodTo)
    next.delete('page')
    setSearch(next, { replace: true })
  }, [active, legacy, periodFrom, periodTo, search, setSearch])

  const setPeriodRange = (from: string, to: string) => {
    const next = new URLSearchParams(search)
    next.delete('periodCode')
    next.set('periodFrom', from)
    next.set('periodTo', to >= from ? to : from)
    next.delete('page')
    setSearch(next)
  }
  return { periods, periodFrom, periodTo, setPeriodRange }
}

export function PeriodRangeSelector({
  periodFrom, periodTo, periods, loading, refreshing, onChange, onRefresh,
}: {
  periodFrom?: string
  periodTo?: string
  periods: Period[]
  loading?: boolean
  refreshing?: boolean
  onChange: (periodFrom: string, periodTo: string) => void
  onRefresh: () => void
}) {
  const options = periods.map((period) => ({ value: period.periodCode, label: period.periodCode }))
  return <Space className="period-selector" size={8} wrap>
    <Typography.Text>会计期间</Typography.Text>
    <Select aria-label="起始会计期间" value={periodFrom} loading={loading} options={options}
      onChange={(value) => onChange(value, periodTo && periodTo >= value ? periodTo : value)} />
    <Typography.Text>至</Typography.Text>
    <Select aria-label="结束会计期间" value={periodTo} loading={loading}
      options={options.filter((option) => !periodFrom || option.value >= periodFrom)}
      onChange={(value) => periodFrom && onChange(periodFrom, value)} />
    <Button aria-label="刷新当前期间范围数据" icon={<ReloadOutlined />} loading={refreshing}
      onClick={onRefresh} />
  </Space>
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
