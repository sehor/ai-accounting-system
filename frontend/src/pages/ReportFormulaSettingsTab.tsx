import { Alert, Button, Card, Segmented, Select, Space, Tag, Typography, message } from 'antd'
import { App as AntApp } from 'antd'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useEffect, useMemo, useState } from 'react'
import { useParams } from 'react-router-dom'
import { apiData, apiHeaders, openApiClient, ApiError } from '../api/client'
import type { components } from '../api/generated'
import type { ApiAuth } from '../api/client'
import { useAuth } from '../auth/AuthProvider'
import { FormulaFixedLineEditor } from './FormulaFixedLineEditor'
import { FormulaDetailRuleEditor } from './FormulaDetailRuleEditor'
import { FormulaPreviewPane } from './FormulaPreviewPane'
import { FormulaVersionDrawer } from './FormulaVersionDrawer'
import { definitionFromJson } from '../features/reportFormulas/types'
import type { FormulaDefinition, FormulaLine } from '../features/reportFormulas/types'

type Workspace = components['schemas']['ReportFormulaWorkspace']
type Draft = components['schemas']['ReportFormulaDraft']
type Account = components['schemas']['Account']

type FormulaCode = 'BALANCE_SHEET' | 'INCOME_STATEMENT'

export function ReportFormulaSettingsTab() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const client = useQueryClient()
  const { modal } = AntApp.useApp()
  const [code, setCode] = useState<FormulaCode>('BALANCE_SHEET')
  const [edited, setEdited] = useState<FormulaDefinition>()
  const [dirty, setDirty] = useState(false)
  const [preview, setPreview] = useState<components['schemas']['ReportFormulaPreviewResult']>()
  const [acknowledged, setAcknowledged] = useState(false)
  const [versionsOpen, setVersionsOpen] = useState(false)
  const [periodCode, setPeriodCode] = useState<string>()
  const [periodFrom, setPeriodFrom] = useState<string>()
  const [periodTo, setPeriodTo] = useState<string>()
  const [messageApi, contextHolder] = message.useMessage()

  const workspace = useQuery({
    queryKey: ['report-formula', ledgerId, code],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/report-formulas/{code}', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
    })),
    enabled: Boolean(session && ledgerId),
  })
  const accounts = useQuery({
    queryKey: ['accounts', ledgerId],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', {
      params: { path: { ledgerId } }, headers: apiHeaders(session!),
    })),
    enabled: Boolean(session && ledgerId),
  })
  const periods = useQuery({
    queryKey: ['periods', ledgerId],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/periods', {
      params: { path: { ledgerId } }, headers: apiHeaders(session!),
    })),
    enabled: Boolean(session && ledgerId),
  })
  const role = useQuery({
    queryKey: ['ledger-role', ledgerId],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/role', {
      params: { path: { ledgerId } }, headers: apiHeaders(session!),
    })),
    enabled: Boolean(session && ledgerId),
  })
  const readOnly = !['OWNER', 'EDITOR'].includes(role.data?.role || '')

  const data = workspace.data
  const definition = useMemo(() => data ? definitionFromJson(data.publishedDefinition) : undefined, [data])
  const draftVersion = data?.draft?.version
  const previewedVersion = data?.draft?.lastPreviewedDraftVersion ?? null
  const previewHasWarnings = Boolean(data?.draft?.previewHasWarnings)
  const balanceSheet = code === 'BALANCE_SHEET'

  // Load the editable definition: the draft when present, else the published one.
  useEffect(() => {
    if (!data) return
    if (data.draft) {
      setEdited(definitionFromJson(data.draft.definition))
    } else {
      setEdited(definitionFromJson(data.publishedDefinition))
    }
    setDirty(false)
    setPreview(undefined)
    setAcknowledged(false)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [data, code])

  // Initialize the preview period selectors once the period list is available.
  useEffect(() => {
    if (!periods.data?.length) return
    setPeriodCode((previous) => previous ?? periods.data![periods.data!.length - 1].periodCode)
    setPeriodFrom((previous) => previous ?? periods.data![0].periodCode)
    setPeriodTo((previous) => previous ?? periods.data![periods.data!.length - 1].periodCode)
  }, [periods.data])

  const refresh = () => {
    void client.invalidateQueries({ queryKey: ['report-formula', ledgerId, code] })
    void client.invalidateQueries({ queryKey: ['report-formula-versions', ledgerId, code] })
    void client.invalidateQueries({ queryKey: ['report', ledgerId] })
  }

  const createDraft = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/report-formulas/{code}/draft', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
    })),
    onSuccess: () => { messageApi.success('草稿已创建'); refresh() },
    onError: (error) => messageApi.error(error instanceof ApiError ? error.message : '创建草稿失败'),
  })

  const saveDraft = useMutation({
    mutationFn: (value: { lines?: unknown[]; rules?: unknown[] }) =>
      apiData(openApiClient.PUT('/v1/ledgers/{ledgerId}/report-formulas/{code}/draft', {
        params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
        body: { expectedDraftVersion: draftVersion!, ...value } as never,
      })),
    onSuccess: (draft: Draft) => {
      setDirty(false)
      setPreview(undefined)
      setAcknowledged(false)
      messageApi.success(`草稿已保存（v${draft.version}）`)
      refresh()
    },
    onError: (error) => handleConflictOrError(error),
  })

  const previewDraft = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/report-formulas/{code}/draft:preview', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
      body: {
        expectedDraftVersion: draftVersion!,
        periodCode: definition?.kind === 'FIXED_LINES' ? (periodCode ?? null) : null,
        periodFrom: definition?.kind === 'FIXED_LINES' ? null : (periodFrom ?? null),
        periodTo: definition?.kind === 'FIXED_LINES' ? null : (periodTo ?? null),
      },
    })),
    onSuccess: (result) => {
      setPreview(result)
      setAcknowledged(false)
      if (result.blockingIssues?.length) {
        messageApi.error('试算存在阻断问题')
      } else if (result.previewHasWarnings) {
        messageApi.warning('试算完成，但存在勾稽警告')
      } else {
        messageApi.success('试算通过')
      }
      refresh()
    },
    onError: (error) => handleConflictOrError(error),
  })

  const publishDraft = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/report-formulas/{code}:publish', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
      body: { expectedPublishedVersion: data?.publishedVersion!, expectedDraftVersion: draftVersion!, acknowledgeWarnings: acknowledged },
    })),
    onSuccess: (result) => {
      messageApi.success(`已发布为版本 v${result.publishedVersion}`)
      setPreview(undefined)
      setDirty(false)
      refresh()
    },
    onError: (error) => handleConflictOrError(error),
  })

  const resetDraft = useMutation({
    mutationFn: () => apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/report-formulas/{code}/draft:reset', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
      body: { expectedDraftVersion: draftVersion! },
    })),
    onSuccess: (draft: Draft) => {
      setDirty(false)
      setPreview(undefined)
      messageApi.success(`草稿已重置为标准定义（v${draft.version}）`)
      refresh()
    },
    onError: (error) => handleConflictOrError(error),
  })

  const discardDraft = useMutation({
    mutationFn: () => apiData(openApiClient.DELETE('/v1/ledgers/{ledgerId}/report-formulas/{code}/draft', {
      params: { path: { ledgerId, code } }, headers: apiHeaders(session!),
    })),
    onSuccess: () => { messageApi.success('草稿已放弃'); setPreview(undefined); setDirty(false); refresh() },
    onError: (error) => handleConflictOrError(error),
  })

  const handleConflictOrError = (error: unknown) => {
    const problem = error instanceof ApiError ? error.problem : undefined
    if (problem?.code === 'REPORT_FORMULA_VERSION_CONFLICT') {
      modal.confirm({
        title: '版本冲突',
        content: '服务器上的公式已经变化。刷新将丢弃本地的未保存修改，且不会覆盖服务器草稿。',
        okText: '刷新',
        cancelText: '取消',
        onOk: () => { refresh() },
      })
      return
    }
    messageApi.error(problem?.detail || '操作失败')
  }

  const onLineChange = (lineKey: string, patch: { name?: string; expression?: unknown }) => {
    if (!edited) return
    setEdited({
      ...edited,
      groups: edited.groups.map((group) => ({
        ...group,
        lines: group.lines.map((line) => line.key === lineKey ? { ...line, ...patch } as FormulaLine : line),
      })),
    })
    setDirty(true)
  }

  const canPreview = Boolean(data?.draft) && !dirty && draftVersion != null && previewDraft.isIdle
  const previewedCurrent = previewedVersion != null && previewedVersion === draftVersion
  const canPublish = Boolean(data?.draft) && !dirty && previewedCurrent && !publishDraft.isPending
    && (acknowledged || !previewHasWarnings)

  return <>{contextHolder}
    <Space direction="vertical" size={16} style={{ width: '100%' }}>
      <Card
        title="报表公式"
        extra={<Space wrap>
          <Segmented
            value={code}
            options={[{ value: 'BALANCE_SHEET', label: '资产负债表' }, { value: 'INCOME_STATEMENT', label: '利润表' }]}
            onChange={(value) => setCode(value as FormulaCode)}
          />
          <Tag>当前发布版本 v{data?.publishedVersion ?? '—'}</Tag>
          <Button onClick={() => setVersionsOpen(true)}>版本历史</Button>
        </Space>}
      >
        {workspace.isError && <Alert type="error" showIcon message="公式读取失败" description={workspace.error instanceof ApiError ? workspace.error.message : undefined} />}
        {data && edited && <>
          <Alert type="info" showIcon style={{ marginBottom: 12 }}
            message={definition?.kind === 'FIXED_LINES'
              ? '行号、分组、顺序、勾稽规则锁定；可修改项目名称和取数公式。'
              : '编辑科目类别与借贷方向；补充科目可选标准科目或账套科目。'}
          />
          <Space wrap style={{ marginBottom: 12 }}>
            {!data.draft && <Button type="primary" loading={createDraft.isPending} onClick={() => createDraft.mutate()} disabled={readOnly}>创建草稿</Button>}
            {data.draft && <>
              <Button
                type="primary"
                loading={saveDraft.isPending}
                disabled={readOnly || !dirty}
                onClick={() => saveDraft.mutate(definition?.kind === 'FIXED_LINES'
                  ? { lines: editedGroupsToLines(edited) }
                  : { rules: edited.rules })}
              >保存草稿</Button>
              <Button loading={previewDraft.isPending} disabled={readOnly || !canPreview} onClick={() => previewDraft.mutate()}>试算</Button>
              <Button disabled={readOnly || !canPublish} onClick={() => publishDraft.mutate()}>发布</Button>
              <Button disabled={readOnly} onClick={() => resetDraft.mutate()} loading={resetDraft.isPending}>重置为标准</Button>
              <Button danger disabled={readOnly} onClick={() => discardDraft.mutate()} loading={discardDraft.isPending}>放弃草稿</Button>
            </>}
          </Space>
          {dirty && <Tag color="orange">有未保存修改</Tag>}
          {definition?.kind === 'FIXED_LINES'
            ? <FormulaFixedLineEditor
                definition={edited}
                onLineChange={onLineChange}
                accounts={accounts.data || []}
                balanceSheet={balanceSheet}
                readOnly={readOnly}
              />
            : <FormulaDetailRuleEditor
                rules={edited.rules}
                onRulesChange={(rules) => { setEdited({ ...edited, rules }); setDirty(true) }}
                accounts={accounts.data || []}
                readOnly={readOnly}
              />}
        </>}
      </Card>
      {data && data.draft && <div className="formula-preview-layout">
        <PeriodSelectors
          kind={definition?.kind}
          balanceSheet={balanceSheet}
          periods={periods.data || []}
          periodCode={periodCode}
          periodFrom={periodFrom}
          periodTo={periodTo}
          onPeriodCode={setPeriodCode}
          onPeriodFrom={setPeriodFrom}
          onPeriodTo={setPeriodTo}
        />
        <FormulaPreviewPane
          result={preview}
          loading={previewDraft.isPending}
          definition={definition}
          canPublish={canPublish}
          publishing={publishDraft.isPending}
          acknowledged={acknowledged}
          onAcknowledge={setAcknowledged}
          onPublish={() => publishDraft.mutate()}
        />
      </div>}
    </Space>
    <FormulaVersionDrawer
      ledgerId={ledgerId}
      code={code}
      auth={session as ApiAuth}
      open={versionsOpen}
      onClose={() => setVersionsOpen(false)}
      hasDraft={Boolean(data?.draft)}
      expectedPublishedVersion={data?.publishedVersion ?? 0}
      onChanged={refresh}
    />
  </>
}

function editedGroupsToLines(definition: FormulaDefinition): unknown[] {
  return definition.groups.flatMap((group) => group.lines)
    .filter((line) => line.rowType !== 'SECTION')
    .map((line) => ({ lineKey: line.key, name: line.name, expression: line.expression }))
}

function PeriodSelectors({ kind, balanceSheet, periods, periodCode, periodFrom, periodTo, onPeriodCode, onPeriodFrom, onPeriodTo }: {
  kind: string | undefined
  balanceSheet: boolean
  periods: { id: string; periodCode: string }[]
  periodCode?: string
  periodFrom?: string
  periodTo?: string
  onPeriodCode: (value: string) => void
  onPeriodFrom: (value: string) => void
  onPeriodTo: (value: string) => void
}) {
  const options = periods.map((period) => ({ value: period.periodCode, label: period.periodCode }))
  if (kind === 'FIXED_LINES') {
    return <Card size="small" title="试算期间"><Select aria-label="试算期间" style={{ width: 160 }} value={periodCode} options={options} onChange={onPeriodCode} /></Card>
  }
  return <Card size="small" title="试算期间">
    <Space>
      <Select aria-label="起始期间" style={{ width: 140 }} value={periodFrom} options={options} onChange={onPeriodFrom} />
      <Typography.Text>至</Typography.Text>
      <Select aria-label="结束期间" style={{ width: 140 }} value={periodTo} options={options} onChange={onPeriodTo} />
    </Space>
  </Card>
}
