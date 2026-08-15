import { vi } from 'vitest'
import { openApiClient, type ApiAuth, type ApiResponse } from '../api/client'

type LegacyFetch = (path: string, auth: ApiAuth, init?: RequestInit) => Promise<unknown>
type LegacyFetchWithHeaders = (path: string, auth: ApiAuth, init?: RequestInit) => Promise<ApiResponse<unknown>>

function authFrom(headers: HeadersInit | undefined): ApiAuth {
  const values = new Headers(headers)
  const authorization = values.get('Authorization')
  const auth: ApiAuth = {}
  if (authorization) auth.accessToken = authorization.replace(/^Bearer\s+/i, '')
  if (values.get('X-User-Id')) auth.localUserId = values.get('X-User-Id')!
  if (values.get('X-User-Name')) auth.localUserName = values.get('X-User-Name')!
  return auth
}

function legacyPath(path: string, options: Record<string, any>): string {
  let result = path.replace(/^\/v1/, '')
  for (const [name, value] of Object.entries(options.params?.path || {})) {
    result = result.replace(`{${name}}`, String(value))
  }
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(options.params?.query || {})) {
    if (value === undefined || value === null || (name === 'includeParents' && value === false)) continue
    query.set(name, String(value))
  }
  const suffix = query.toString()
  return suffix ? `${result}?${suffix}` : result
}

export function installLegacyOpenApiBridge(fetch: LegacyFetch, fetchWithHeaders?: LegacyFetchWithHeaders) {
  const bridge = (method: string) => async (path: string, options: Record<string, any> = {}) => {
    const target = legacyPath(path, options)
    const auth = authFrom(options.headers)
    const init = method === 'GET' ? undefined : {
      method,
      body: options.body === undefined ? undefined : JSON.stringify(options.body),
    }
    const useHeaders = Boolean(fetchWithHeaders && /\/reports\/(?:income-statement|balance-sheet|statutory\/)/.test(target))
    if (useHeaders) {
      const result = init ? await fetchWithHeaders!(target, auth, init) : await fetchWithHeaders!(target, auth)
      return { data: result.data, response: new Response(null, { status: 200, headers: result.headers }) }
    }
    const data = init ? await fetch(target, auth, init) : await fetch(target, auth)
    return { data, response: new Response(null, { status: 200 }) }
  }
  vi.spyOn(openApiClient, 'GET').mockImplementation(bridge('GET') as never)
  vi.spyOn(openApiClient, 'POST').mockImplementation(bridge('POST') as never)
  vi.spyOn(openApiClient, 'PUT').mockImplementation(bridge('PUT') as never)
  vi.spyOn(openApiClient, 'PATCH').mockImplementation(bridge('PATCH') as never)
  vi.spyOn(openApiClient, 'DELETE').mockImplementation(bridge('DELETE') as never)
}
