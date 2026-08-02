import { describe, expect, it, vi } from 'vitest'
import { ApiError, apiFetch, createIdempotencyKey } from './client'

describe('api client', () => {
  it('maps RFC problem details and auth headers', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify({ title: '无权限', code: 'FORBIDDEN', traceId: 'trace-1' }), { status: 403, headers: { 'content-type': 'application/problem+json' } })))
    await expect(apiFetch('/ledgers', { localUserId: '00000000-0000-0000-0000-000000000001' })).rejects.toMatchObject({ status: 403, problem: { code: 'FORBIDDEN', traceId: 'trace-1' } } satisfies Partial<ApiError>)
    expect(fetch).toHaveBeenCalledWith('/v1/ledgers', expect.objectContaining({ headers: expect.any(Headers) }))
  })

  it('creates stable-looking idempotency keys', () => {
    expect(createIdempotencyKey()).toMatch(/^[0-9a-f-]{36}$/)
  })

  it('clears the stored session after an unauthorized response', async () => {
    sessionStorage.setItem('ai-accounting.session', JSON.stringify({ accessToken: 'expired' }))
    window.history.replaceState({}, '', '/login')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(null, { status: 401 })))

    await expect(apiFetch('/ledgers', { accessToken: 'expired' })).rejects.toMatchObject({ status: 401 })
    expect(sessionStorage.getItem('ai-accounting.session')).toBeNull()
  })
})
