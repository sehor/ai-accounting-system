import { DownloadOutlined, PlusOutlined, UploadOutlined } from '@ant-design/icons'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { Alert, App as AntApp, Button, Card, Checkbox, Empty, Input, Modal, Pagination, Space, Table, Typography, Upload } from 'antd'
import { useMemo, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ApiError, apiData, apiHeaders, apiResponse, createIdempotencyKey, openApiClient } from '../api/client'
import type { components } from '../api/generated'
import { useAuth } from '../auth/AuthProvider'
import { PeriodRangeSelector, usePeriodRangeFilter } from '../components/PeriodSelector'
import { useWorkspaceSearchParams } from '../components/workspaceSearch'
import { useWorkspaceTabs } from '../components/workspaceTabs'

type Account = components['schemas']['Account']
type KingdeeImportResult = components['schemas']['KingdeeImportResult']
type Voucher = components['schemas']['Voucher']
type VoucherLine = components['schemas']['VoucherLineResponse']

type DisplayRow = {
  key: string
  voucher: Voucher
  line: VoucherLine | null
  lineIndex: number
  lineCount: number
}

const amount = (value?: string) => value
  ? Number(value).toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
  : ''
const periodLabel = (periodCode: string) => `${periodCode.slice(0, 4)}年第${Number(periodCode.slice(5))}期`
const laterDate = (first?: string, second?: string) => first && second
  ? (first > second ? first : second) : (first || second || '')
const earlierDate = (first?: string, second?: string) => first && second
  ? (first < second ? first : second) : (first || second || '')

export function VoucherListPage() {
  const { ledgerId = '' } = useParams()
  const { session } = useAuth()
  const navigate = useNavigate()
  const client = useQueryClient()
  const { closeTab } = useWorkspaceTabs()
  const { message, modal } = AntApp.useApp()
  const [search, setSearch] = useWorkspaceSearchParams()
  const { periods, periodFrom, periodTo, setPeriodRange } = usePeriodRangeFilter(ledgerId)
  const limit = Number(search.get('limit') || 20)
  const offset = Number(search.get('offset') || 0)
  const startDate = search.get('startDate') || ''
  const endDate = search.get('endDate') || ''
  const keyword = search.get('keyword') || ''
  const [selectedKeys, setSelectedKeys] = useState<string[]>([])
  const [bulkAction, setBulkAction] = useState<'approve' | null>(null)
  const [bulkComment, setBulkComment] = useState('')
  const [exportOpen, setExportOpen] = useState(false)
  const [mergeEntries, setMergeEntries] = useState(false)
  const periodStartDate = periods.data?.find((period) => period.periodCode === periodFrom)?.startDate
  const periodEndDate = periods.data?.find((period) => period.periodCode === periodTo)?.endDate
  const effectiveStartDate = laterDate(periodStartDate, startDate)
  const effectiveEndDate = earlierDate(periodEndDate, endDate)
  const rangeLabel = periodFrom && periodTo
    ? `${periodLabel(periodFrom)}${periodFrom === periodTo ? '' : `至${periodLabel(periodTo)}`}`
    : '当前期间范围'

  const query = useQuery({
    queryKey: ['vouchers', ledgerId, periodFrom, periodTo, effectiveStartDate, effectiveEndDate, keyword, limit, offset],
    queryFn: async () => {
      const response = await apiResponse(openApiClient.GET('/v1/ledgers/{ledgerId}/vouchers', {
        headers: apiHeaders(session!),
        params: { path: { ledgerId }, query: { limit, offset, startDate: effectiveStartDate, endDate: effectiveEndDate, keyword } },
      }))
      return { data: response.data, total: Number(response.response.headers.get('X-Total-Count') || response.data.length) }
    },
    enabled: Boolean(session && ledgerId && periodFrom && periodTo && effectiveStartDate && effectiveEndDate),
  })
  const accounts = useQuery({
    queryKey: ['accounts', ledgerId],
    queryFn: () => apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/accounts', { headers: apiHeaders(session!), params: { path: { ledgerId } } })),
    enabled: Boolean(session && ledgerId),
  })
  const accountById = useMemo(
    () => new Map((accounts.data || []).map((account) => [account.id, account])), [accounts.data],
  )
  const vouchers = useMemo(() => query.data?.data || [], [query.data?.data])
  const total = query.data?.total || 0
  const rows = useMemo<DisplayRow[]>(() => vouchers.flatMap((voucher) => {
    const lines: Array<VoucherLine | null> = voucher.lines.length ? voucher.lines : [null]
    return lines.map((line, lineIndex) => ({
      key: `${voucher.id}-${line?.id || 'empty'}`,
      voucher,
      line,
      lineIndex,
      lineCount: lines.length,
    }))
  }), [vouchers])
  const selected = vouchers.filter((voucher) => selectedKeys.includes(voucher.id))
  const reviewable = selected.filter((voucher) => voucher.status === 'SUBMITTED')
  const postable = selected.filter((voucher) => voucher.status === 'APPROVED'
    || (voucher.status === 'VALIDATED' && !voucher.approvalRequired))

  const batch = useMutation({
    mutationFn: async ({ action, selectedVouchers, comment }: {
      action: 'approve' | 'post'; selectedVouchers: Voucher[]; comment?: string
    }) => {
      const results = await Promise.allSettled(selectedVouchers.map((voucher) => action === 'approve'
        ? apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:approve', {
            headers: apiHeaders(session!), params: { path: { ledgerId, voucherId: voucher.id } }, body: { comment: comment! },
          }))
        : apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/vouchers/{voucherId}:post', {
            headers: apiHeaders(session!), params: { path: { ledgerId, voucherId: voucher.id } },
          }))))
      return {
        succeeded: results.filter((result) => result.status === 'fulfilled').length,
        failed: results.filter((result) => result.status === 'rejected').length,
      }
    },
    onSuccess: ({ succeeded, failed }) => {
      setSelectedKeys([])
      setBulkAction(null)
      setBulkComment('')
      void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] })
      if (failed) message.warning(`已完成 ${succeeded} 张，${failed} 张失败`)
      else message.success(`已完成 ${succeeded} 张凭证`)
    },
  })
  const importKingdee = useMutation({
    mutationFn: (file: File) => {
      const body = new FormData()
      body.append('file', file)
      return apiData(openApiClient.POST('/v1/ledgers/{ledgerId}/data-exchange/kingdee:import', {
        headers: { ...apiHeaders(session!), 'Idempotency-Key': createIdempotencyKey() },
        params: { path: { ledgerId } },
        body: { file: file as unknown as string },
        bodySerializer: () => body,
      }))
    },
    onSuccess: (result) => {
      message.success(`已导入 ${result.voucherCount} 张凭证、${result.rowCount} 条分录`)
      void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] })
      void client.invalidateQueries({ queryKey: ['periods', ledgerId] })
    },
    onError: (error) => message.error(error instanceof ApiError ? error.message : '金蝶凭证导入失败'),
  })
  const exportKingdee = useMutation({
    mutationFn: (shouldMerge: boolean) => {
      return apiData(openApiClient.GET('/v1/ledgers/{ledgerId}/data-exchange/kingdee:export', {
        headers: apiHeaders(session!),
        params: { path: { ledgerId }, query: { mergeEntries: shouldMerge, startDate: effectiveStartDate, endDate: effectiveEndDate } },
        parseAs: 'blob',
      }))
    },
    onSuccess: (blob) => {
      setExportOpen(false)
      const url = URL.createObjectURL(blob)
      const anchor = document.createElement('a')
      anchor.href = url
      anchor.download = 'kingdee-vouchers.xlsx'
      anchor.click()
      URL.revokeObjectURL(url)
    },
    onError: (error) => message.error(error instanceof ApiError ? error.message : '金蝶凭证导出失败'),
  })
  const removeVoucher = useMutation({
    mutationFn: (voucher: Voucher) => apiData(openApiClient.DELETE('/v1/ledgers/{ledgerId}/vouchers/{voucherId}', {
      headers: apiHeaders(session!), params: { path: { ledgerId, voucherId: voucher.id } },
    })),
    onSuccess: (_, voucher) => {
      setSelectedKeys((current) => current.filter((key) => key !== voucher.id))
      client.removeQueries({ queryKey: ['voucher', ledgerId, voucher.id], exact: true })
      closeTab(`voucher-${voucher.id}`, { discardChanges: true })
      message.success(`凭证 ${voucher.voucherType}-${voucher.voucherNumber} 删除成功`)
      void client.invalidateQueries({ queryKey: ['vouchers', ledgerId] })
    },
    onError: (error) => message.error(error instanceof ApiError ? error.message : '删除凭证失败'),
  })

  const accountLabel = (accountId?: string) => {
    if (!accountId) return '—'
    const account = accountById.get(accountId)
    return account ? `${account.code} ${account.name}` : accountId
  }
  const mergedCell = (row: DisplayRow) => ({ rowSpan: row.lineIndex === 0 ? row.lineCount : 0 })
  const mergedTopCell = (row: DisplayRow) => ({
    rowSpan: row.lineIndex === 0 ? row.lineCount : 0,
    style: { verticalAlign: 'top' },
  })
  const confirmRemove = (voucher: Voucher) => modal.confirm({
    title: '确认删除凭证？',
    content: `将删除凭证 ${voucher.voucherType}-${voucher.voucherNumber}，此操作不可撤销。`,
    okText: '删除',
    okButtonProps: { danger: true },
    cancelText: '取消',
    onOk: () => removeVoucher.mutateAsync(voucher),
  })
  const toggleVoucher = (voucherId: string, checked: boolean) => setSelectedKeys((current) => checked
    ? [...new Set([...current, voucherId])]
    : current.filter((key) => key !== voucherId))
  const selectAll = (checked: boolean) => setSelectedKeys(checked ? vouchers.map((voucher) => voucher.id) : [])
  const changePage = (page: number) => {
    const next = new URLSearchParams(search)
    next.set('limit', String(limit))
    next.set('offset', String((page - 1) * limit))
    setSelectedKeys([])
    setSearch(next)
  }
  const setFilter = (name: 'startDate' | 'endDate' | 'keyword', value: string) => {
    const next = new URLSearchParams(search)
    if (value) next.set(name, value)
    else next.delete(name)
    next.delete('offset')
    next.delete('page')
    setSelectedKeys([])
    setSearch(next)
  }

  return <section className="financial-page" aria-labelledby="voucher-list-title">
    <div className="financial-toolbar voucher-toolbar">
      <Typography.Title id="voucher-list-title" level={1}>查凭证</Typography.Title>
      <PeriodRangeSelector
        periodFrom={periodFrom}
        periodTo={periodTo}
        periods={periods.data || []}
        loading={periods.isLoading}
        refreshing={query.isFetching}
        onChange={setPeriodRange}
        onRefresh={() => void query.refetch()}
      />
      <Space wrap>
        <Input aria-label="凭证关键字" value={keyword} allowClear placeholder="摘要、字号或凭证字"
          onChange={(event) => setFilter('keyword', event.target.value)} />
        <Input aria-label="开始日期" type="date" value={startDate}
          onChange={(event) => setFilter('startDate', event.target.value)} />
        <Input aria-label="结束日期" type="date" value={endDate}
          onChange={(event) => setFilter('endDate', event.target.value)} />
      </Space>
      <Space className="financial-toolbar-actions" wrap>
        <Upload accept=".xls,.xlsx" showUploadList={false} beforeUpload={(file) => {
          const name = file.name.toLowerCase()
          if ((!name.endsWith('.xls') && !name.endsWith('.xlsx')) || file.size > 10 * 1024 * 1024) {
            message.error('仅支持不超过 10 MiB 的 .xls/.xlsx 文件')
            return Upload.LIST_IGNORE
          }
          importKingdee.mutate(file)
          return false
        }}><Button icon={<UploadOutlined />} loading={importKingdee.isPending}>导入金蝶凭证</Button></Upload>
        <Button icon={<DownloadOutlined />} loading={exportKingdee.isPending}
          disabled={!effectiveStartDate || !effectiveEndDate} onClick={() => setExportOpen(true)}>导出金蝶凭证</Button>
        {reviewable.length > 0 && <Button onClick={() => setBulkAction('approve')}>批量审核</Button>}
        {postable.length > 0 && <Button loading={batch.isPending} onClick={() => batch.mutate({ action: 'post', selectedVouchers: postable })}>批量记账</Button>}
        <Button type="primary" icon={<PlusOutlined />} onClick={() => navigate(`/ledgers/${ledgerId}/vouchers/new`)}>新建凭证</Button>
      </Space>
    </div>
    {query.isError && <Alert type="error" showIcon message="凭证列表读取失败" />}
    <Card className="financial-grid-card voucher-grid-card">
      <Table<DisplayRow>
        rowKey="key"
        size="small"
        className="financial-table voucher-table"
        loading={query.isLoading || !periodFrom || !periodTo}
        dataSource={rows}
        locale={{ emptyText: <Empty description={`${rangeLabel}没有凭证数据`} /> }}
        pagination={false}
        scroll={{ x: 1260 }}
        columns={[
          { title: <Checkbox aria-label="选择本页全部凭证" checked={vouchers.length > 0 && selectedKeys.length === vouchers.length}
              indeterminate={selectedKeys.length > 0 && selectedKeys.length < vouchers.length}
              onChange={(event) => selectAll(event.target.checked)} />, width: 44, align: 'center', onCell: mergedCell,
            render: (_, row) => row.lineIndex === 0 ? <Checkbox aria-label={`选择凭证 ${row.voucher.voucherType}-${row.voucher.voucherNumber}`}
              checked={selectedKeys.includes(row.voucher.id)} onChange={(event) => toggleVoucher(row.voucher.id, event.target.checked)} /> : null },
          { title: '日期', width: 110, onCell: mergedTopCell, render: (_, row) => row.lineIndex === 0 ? row.voucher.voucherDate : null },
          { title: '凭证字号', width: 110, onCell: mergedTopCell, render: (_, row) => row.lineIndex === 0
            ? <Link to={`/ledgers/${ledgerId}/vouchers/${row.voucher.id}`}>{row.voucher.voucherType}-{row.voucher.voucherNumber}</Link>
            : null },
          { title: '摘要', width: 260, render: (_, row) => row.line?.summary || row.voucher.summary || '—' },
          { title: '科目', width: 360, render: (_, row) => accountLabel(row.line?.accountId) },
          { title: '借方金额', width: 140, align: 'right', render: (_, row) => row.line?.side === 'DEBIT' ? amount(row.line.baseAmount) : '' },
          { title: '贷方金额', width: 140, align: 'right', render: (_, row) => row.line?.side === 'CREDIT' ? amount(row.line.baseAmount) : '' },
          { title: '操作', width: 100, fixed: 'right', onCell: mergedCell, render: (_, row) => row.lineIndex === 0
            ? <Button type="link" danger loading={removeVoucher.isPending && removeVoucher.variables?.id === row.voucher.id}
                onClick={() => confirmRemove(row.voucher)}>删除</Button>
            : null },
        ]}
      />
      {total > limit && <div className="financial-pagination">
        <Pagination
          current={Math.floor(offset / limit) + 1}
          pageSize={limit}
          total={total}
          showSizeChanger={false}
          showTotal={(total) => `共 ${total} 张凭证`}
          onChange={changePage}
        />
      </div>}
    </Card>
    <Modal open={bulkAction === 'approve'} title={`批量审核（${reviewable.length} 张）`} okText="确认审核"
      cancelText="取消" confirmLoading={batch.isPending} okButtonProps={{ disabled: !bulkComment.trim() }}
      onCancel={() => setBulkAction(null)} onOk={() => batch.mutate({ action: 'approve', selectedVouchers: reviewable, comment: bulkComment.trim() })}>
      <Input.TextArea rows={3} value={bulkComment} onChange={(event) => setBulkComment(event.target.value)} placeholder="请输入审核意见（必填）" />
    </Modal>
    <Modal open={exportOpen} title="导出金蝶凭证" okText="导出" cancelText="取消"
      confirmLoading={exportKingdee.isPending} onCancel={() => setExportOpen(false)} onOk={() => exportKingdee.mutate(mergeEntries)}>
      <Space direction="vertical" size={4}>
        <Typography.Text>导出范围：{rangeLabel}</Typography.Text>
        <Checkbox checked={mergeEntries} onChange={(event) => setMergeEntries(event.target.checked)}>合并同类分录</Checkbox>
        <Typography.Text type="secondary">仅合并同月、同银行，且一级科目符合“收款-主营、付款-日常、付款-主营、银行费用”之一的凭证。</Typography.Text>
      </Space>
    </Modal>
  </section>
}
