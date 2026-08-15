import { Alert, Form, Input, Select, Space } from 'antd'
import type { FormInstance } from 'antd'
import { useQuery } from '@tanstack/react-query'
import { useEffect, useMemo } from 'react'
import { apiData, apiHeaders, openApiClient, type ApiAuth } from '../api/client'
import type { components } from '../api/generated'

type Account = components['schemas']['Account']

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
  const dimensionTypeIds = useMemo(
    () => [...new Set(requirements.map((requirement) => requirement.dimensionTypeId))].sort(),
    [requirementKey],
  )
  const values = useQuery({
    queryKey: ['dimension-values', ledgerId, dimensionTypeIds],
    queryFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/dimension-values:batch', {
      params: { path: { ledgerId } }, headers: apiHeaders(auth), body: { dimensionTypeIds },
    })),
    enabled: Boolean(ledgerId && accountId && dimensionTypeIds.length),
  })
  const valuesByType = useMemo(
    () => new Map((values.data?.groups || []).map((group) => [group.dimensionTypeId, group.values])),
    [values.data],
  )

  useEffect(() => {
    const current: OpeningDimensionInput[] = form.getFieldValue(['lines', lineIndex, 'dimensions']) || []
    const byType = new Map(current.map((dimension) => [dimension.dimensionTypeId, dimension.dimensionValueId]))
    form.setFieldValue(['lines', lineIndex, 'dimensions'], requirements.map((requirement) => ({
      dimensionTypeId: requirement.dimensionTypeId,
      dimensionValueId: byType.get(requirement.dimensionTypeId),
    })))
  }, [form, lineIndex, requirementKey])

  if (!account) return <span>请先选择科目</span>
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
          loading={values.isLoading}
          status={values.isError ? 'error' : undefined}
          placeholder={values.isError ? '加载失败' : `选择${requirement.name}`}
          options={(valuesByType.get(requirement.dimensionTypeId) || [])
            .filter((value) => value.status === 'ACTIVE')
            .map((value) => ({ value: value.id, label: `${value.code} ${value.name}` }))}
        />
      </Form.Item>
    </div>)}
  </Space>
}
