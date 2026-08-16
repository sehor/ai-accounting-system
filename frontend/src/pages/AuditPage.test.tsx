import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from 'vitest'
import { openApiClient } from '../api/client'
import { AuditPage } from './AuditPage'

vi.mock('../api/client', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../api/client')>()
  return { ...actual, openApiClient: { GET: vi.fn() } }
})

vi.mock('../auth/AuthProvider', () => ({
  useAuth: () => ({ session: { localUserId: 'user-1', localUserName: 'admin' } }),
}))

const getMock = openApiClient.GET as unknown as ReturnType<typeof vi.fn>
const page = (id: string, hasMore: boolean, nextCursor?: string) => ({
  data: {
    items: [{
      id, aggregateType: 'VOUCHER', aggregateId: `aggregate-${id}`, revision: 1,
      action: 'UPDATE', actorId: 'user-1', createdAt: '2026-08-15T10:00:00Z',
    }],
    hasMore,
    nextCursor,
  },
  response: new Response(null, { status: 200 }),
})

describe('AuditPage', () => {
  beforeAll(() => {
    window.matchMedia = vi.fn().mockImplementation((query: string) => ({
      matches: false, media: query, onchange: null,
      addListener: vi.fn(), removeListener: vi.fn(),
      addEventListener: vi.fn(), removeEventListener: vi.fn(), dispatchEvent: vi.fn(),
    }))
  })
  beforeEach(() => {
    getMock.mockImplementation((_path: string, options: { params: { query: { cursor?: string } } }) => (
      Promise.resolve(options.params.query.cursor ? page('audit-2', false) : page('audit-1', true, 'cursor-2'))
    ))
  })
  afterEach(cleanup)

  it('loads cursor pages and resets the cursor when filters change', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}>
      <MemoryRouter initialEntries={['/ledgers/ledger-1/audit']}>
        <Routes><Route path="/ledgers/:ledgerId/audit" element={<AuditPage />} /></Routes>
      </MemoryRouter>
    </QueryClientProvider>)

    await waitFor(() => expect(document.querySelector('[data-row-key="audit-1"]')).toBeInTheDocument())
    fireEvent.click(screen.getByRole('button', { name: '加载更多' }))
    await waitFor(() => expect(document.querySelector('[data-row-key="audit-2"]')).toBeInTheDocument())
    expect(getMock.mock.calls.at(-1)?.[1].params.query.cursor).toBe('cursor-2')

    fireEvent.change(screen.getByRole('textbox', { name: '按对象类型筛选' }), { target: { value: 'VOUCHER' } })
    await waitFor(() => expect(getMock.mock.calls.at(-1)?.[1].params.query).toMatchObject({
      aggregateType: 'VOUCHER', cursor: undefined,
    }))
  })
})
