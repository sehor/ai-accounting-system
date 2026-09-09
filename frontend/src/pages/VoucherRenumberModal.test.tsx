import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { ApiError, openApiClient } from '../api/client'
import { VoucherRenumberModal } from './VoucherRenumberModal'

vi.mock('../api/client', async (importOriginal) => ({
  ...await importOriginal<typeof import('../api/client')>(),
  openApiClient: { POST: vi.fn() },
}))
vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ session: { localUserName: 'admin' } }) }))

const post = vi.mocked(openApiClient.POST)
const complete = vi.fn()
const period = { id: 'period-1', ledgerId: 'ledger-1', periodCode: '2026-01', startDate: '2026-01-01', endDate: '2026-01-31', status: 'OPEN', hasVouchers: true }
const preview = {
  periodId: period.id, totalCount: 3, changedCount: 2, firstNumber: '1', lastNumber: '3', previewFingerprint: 'fingerprint',
  changes: [{ voucherId: 'voucher-1', voucherDate: '2026-01-02', summary: '办公费用', oldNumber: '4', newNumber: '1' }],
}
const response = (data: unknown) => ({ data, response: new Response(null, { status: 200 }) })
const mount = (initialPeriodId: string | undefined = period.id, status = 'OPEN') => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
    <App><VoucherRenumberModal ledgerId="ledger-1" periods={[{ ...period, status }]} initialPeriodId={initialPeriodId}
      onCancel={vi.fn()} onComplete={complete} /></App>
  </QueryClientProvider>,
)
beforeAll(() => {
  window.matchMedia = (query: string) => ({ matches: false, media: query, onchange: null, addListener: vi.fn(), removeListener: vi.fn(), addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn() })
  const computedStyle = window.getComputedStyle
  window.getComputedStyle = ((element: Element) => computedStyle(element)) as typeof window.getComputedStyle
})
beforeEach(() => { vi.clearAllMocks(); post.mockResolvedValue(response(preview) as never) })
afterEach(cleanup)

describe('整理凭证号', () => {
  it('defaults to date order including gap removal and requires a preview before execution', async () => {
    mount()
    expect(screen.getByLabelText('按凭证日期重新编号，并补齐缺号')).toBeChecked()
    expect(screen.getByRole('button', { name: '确认整理' })).toBeDisabled()
    fireEvent.click(screen.getByRole('button', { name: '预览整理' }))
    await screen.findByText('办公费用')
    expect(post).toHaveBeenCalledWith(expect.stringContaining('renumber:preview'), expect.objectContaining({ body: { periodId: 'period-1', mode: 'DATE_ORDER', startNumber: '1' } }))
    fireEvent.change(screen.getByLabelText('起始凭证号'), { target: { value: '5' } })
    expect(screen.queryByText('办公费用')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '确认整理' })).toBeDisabled()
    fireEvent.click(screen.getByLabelText('按原凭证号顺序补齐缺号'))
    fireEvent.click(screen.getByRole('button', { name: '预览整理' }))
    await screen.findByText('办公费用')
    expect(post).toHaveBeenLastCalledWith(expect.any(String), expect.objectContaining({ body: { periodId: 'period-1', mode: 'NUMBER_ORDER', startNumber: '5' } }))
  })

  it('requires an explicit period for a cross-period list and rejects closed periods', () => {
    mount('')
    expect(screen.getByRole('button', { name: '预览整理' })).toBeDisabled()
    cleanup()
    mount(period.id, 'CLOSED')
    expect(screen.getByRole('button', { name: '预览整理' })).toBeDisabled()
    expect(screen.getByText('已结账期间不能整理凭证号，请先反结账。')).toBeInTheDocument()
  })

  it('shows a normal no-change result and prevents execution', async () => {
    post.mockResolvedValue(response({ ...preview, changedCount: 0, changes: [] }) as never)
    mount()
    fireEvent.change(screen.getByLabelText('起始凭证号'), { target: { value: '0' } })
    expect(screen.getByRole('button', { name: '预览整理' })).toBeDisabled()
    fireEvent.change(screen.getByLabelText('起始凭证号'), { target: { value: '1' } })
    fireEvent.click(screen.getByRole('button', { name: '预览整理' }))
    await screen.findByText('当前期间无需整理凭证号')
    expect(screen.getByRole('button', { name: '确认整理' })).toBeDisabled()
  })

  it('discards a stale preview and requires a new preview', async () => {
    mount()
    fireEvent.click(screen.getByRole('button', { name: '预览整理' }))
    await screen.findByText('办公费用')
    post.mockRejectedValueOnce(new ApiError(409, { code: 'VOUCHER_RENUMBER_PREVIEW_STALE', detail: '凭证已发生变化，请重新预览' }))
    fireEvent.click(screen.getByRole('button', { name: '确认整理' }))
    await screen.findByText('凭证已发生变化，请重新预览')
    expect(screen.getByRole('button', { name: '确认整理' })).toBeDisabled()
    expect(complete).not.toHaveBeenCalled()
  })

  it('reuses its idempotency key after an uncertain network result', async () => {
    mount()
    fireEvent.click(screen.getByRole('button', { name: '预览整理' }))
    await screen.findByText('办公费用')
    post.mockRejectedValueOnce(new Error('网络连接中断'))
    fireEvent.click(screen.getByRole('button', { name: '确认整理' }))
    await screen.findByText('网络连接中断')
    const firstOptions = post.mock.calls[1][1] as unknown as { headers: HeadersInit; body: unknown }
    post.mockResolvedValueOnce(response({ operationId: 'op-1', periodId: period.id, changedCount: 2, firstNumber: '1', lastNumber: '3' }) as never)
    fireEvent.click(screen.getByRole('button', { name: '确认整理' }))
    await waitFor(() => expect(complete).toHaveBeenCalledWith(period.id))
    const secondOptions = post.mock.calls[2][1] as unknown as { headers: HeadersInit; body: unknown }
    expect(new Headers(secondOptions?.headers).get('Idempotency-Key')).toBe(new Headers(firstOptions?.headers).get('Idempotency-Key'))
    expect(firstOptions?.body).toEqual({ periodId: period.id, mode: 'DATE_ORDER', startNumber: '1', previewFingerprint: 'fingerprint' })
  })
})
