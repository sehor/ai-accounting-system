import type { ProblemDetails } from './types'
import { clearSession } from '../auth/session'

export interface ApiAuth {
  accessToken?: string
  localUserId?: string
  localUserName?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly problem: ProblemDetails

  constructor(status: number, problem: ProblemDetails) {
    super(problem.detail || problem.title || `请求失败（${status}）`)
    this.name = 'ApiError'
    this.status = status
    this.problem = problem
  }
}

const baseUrl = (import.meta.env.VITE_API_BASE_URL || '/v1').replace(/\/$/, '')

export interface ApiResponse<T> {
  data: T
  headers: Headers
}

export async function apiFetchWithHeaders<T>(path: string, auth: ApiAuth, init: RequestInit = {}): Promise<ApiResponse<T>> {
  const headers = new Headers(init.headers)
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json')
  if (auth.accessToken) headers.set('Authorization', `Bearer ${auth.accessToken}`)
  if (auth.localUserId) headers.set('X-User-Id', auth.localUserId)
  if (auth.localUserName) headers.set('X-User-Name', auth.localUserName)

  const response = await fetch(`${baseUrl}${path}`, { ...init, headers })
  if (response.status === 401) {
    clearSession()
    if (window.location.pathname !== '/login') window.location.assign('/login')
  }
  if (!response.ok) {
    let problem: ProblemDetails = {}
    try {
      problem = (await response.json()) as ProblemDetails
    } catch {
      problem = { title: response.statusText }
    }
    throw new ApiError(response.status, problem)
  }
  if (response.status === 204) return { data: undefined as T, headers: response.headers }
  const contentType = response.headers.get('content-type') || ''
  const data = contentType.includes('application/json')
    ? (await response.json()) as T
    : (await response.blob()) as T
  return { data, headers: response.headers }
}

export async function apiFetch<T>(path: string, auth: ApiAuth, init: RequestInit = {}): Promise<T> {
  return (await apiFetchWithHeaders<T>(path, auth, init)).data
}

export function jsonBody(value: unknown): BodyInit {
  return JSON.stringify(value)
}

export function createIdempotencyKey(): string {
  return crypto.randomUUID()
}

export function documentContentUrl(ledgerId: string, documentId: string): string {
  return `${baseUrl}/ledgers/${ledgerId}/documents/${documentId}/content`
}
