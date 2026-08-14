import { Alert, Form, Input, Select, Space } from 'antd'
import type { FormInstance } from 'antd'
import { useQueries } from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { apiFetch, type ApiAuth } from '../api/client'
import type { Account, DimensionValue } from '../api/types'

export interface OpeningDimensionInput {
  dimensionTypeId: string
  dimensionValueId?: string
}

export interface OpeningFormLine {
  accountId: string
  periodId: string
  currency: string
  dimensionKey?: string
  dimensions: OpeningDimensionInput[]
  debitOriginal: string
  creditOriginal: string
  exchangeRate: string
}

export function OpeningDimensionFields({
  ledgerId, auth, accounts, form, lineIndex,
}: {
  ledgerId: string
  auth: ApiAuth
  accounts: Account[]
  form: FormInstance<{ lines: OpeningFormLine[] }>
  lineIndex: number
}) {
  const accountId = Form.useWatch(['lines', lineIndex, 'accountId'], form)
  const legacyKey = Form.useWatch(['lines', lineIndex, 'dimensionKey'], form)
  const account = useMemo(
    () => accounts.find((candidate) => candidate.id === accountId),
    [accountId, accounts],
  )
  const requirements = account?.dimensionRequirements || []
  const requirementKey = requirements.map((requirement) => requirement.dimensionTypeId).join('|')
  const valueQueries = useQueries({
    queries: requirements.map((requirement) => ({
      queryKey: ['dimension-values', ledgerId, requirement.dimensionTypeId],
      queryFn: () => apiFetch<DimensionValue[]>(
        `/ledgers/${ledgerId}/dimension-types/${requirement.dimensionTypeId}/values`, auth,
      ),
      enabled: Boolean(ledgerId && accountId),
    })),
  })

  useEffect(() => {
    const current = form.getFieldValue(['lines', lineIndex, 'dimensions']) || []
    const byType = new Map(current.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId]))
    form.setFieldValue(['lines', lineIndex, 'dimensions'], requirements.map((requirement) => ({
      dimensionTypeId: requirement.dimensionTypeId,
      dimensionValueId: byType.get(requirement.dimensionTypeId),
    })))
  }, [form, lineIndex, requirementKey])

  if (!accountId) return <span>请先选择科目</span>
  if (requirements.length === 0) {
    return legacyKey
      ? <Alert type="warning" showIcon message={`历史未映射维度：${legacyKey}`} />
      : <span>无需辅助核算</span>
  }
  return <Space direction="vertical" size={4} style={{ width: '100%' }}>
    {legacyKey && <Alert type="warning" showIcon message="历史维度键待映射" description={legacyKey} />}
    {requirements.map((requirement, index) => <div key={requirement.dimensionTypeId}>
      <Form.Item name={['lines', lineIndex, 'dimensions', index, 'dimensionTypeId']} hidden>
        <Input />
      </Form.Item>
      <Form.Item
        name={['lines', lineIndex, 'dimensions', index, 'dimensionValueId']}
        label={`${requirement.name}${requirement.required ? '（必填）' : ''}`}
        rules={requirement.required ? [{ required: true, message: `请选择${requirement.name}` }] : []}
        style={{ marginBottom: 4 }}
      >
        <Select
          aria-label={`${account.code} ${requirement.name}`}
          allowClear={!requirement.required}
          loading={valueQueries[index]?.isLoading}
          status={valueQueries[index]?.isError ? 'error' : undefined}
          placeholder={valueQueries[index]?.isError ? '加载失败' : `选择${requirement.name}`}
          options={(valueQueries[index]?.data || [])
            .filter((value) => value.status === 'ACTIVE')
            .map((value) => ({ value: value.id, label: `${value.code} ${value.name}` }))}
        />
      </Form.Item>
    </div>)}
  </Space>
}
