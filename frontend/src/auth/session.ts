import { UserManager, WebStorageStateStore, type User as OidcUser } from 'oidc-client-ts'

export interface Session {
  accessToken?: string
  localUserId?: string
  localUserName?: string
}

const SESSION_KEY = 'ai-accounting.session'
const LOCAL_USER_KEY_PREFIX = 'ai-accounting.local-user.'
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

export function getSession(): Session | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as Session
  } catch {
    sessionStorage.removeItem(SESSION_KEY)
    return null
  }
}

export function saveSession(session: Session): void {
  sessionStorage.setItem(SESSION_KEY, JSON.stringify(session))
}

export function clearSession(): void {
  sessionStorage.removeItem(SESSION_KEY)
}

export function isOidcConfigured(): boolean {
  return Boolean(import.meta.env.VITE_OIDC_AUTHORITY && import.meta.env.VITE_OIDC_CLIENT_ID)
}

export function isLocalAuthEnabled(): boolean {
  return import.meta.env.DEV && import.meta.env.VITE_LOCAL_AUTH_ENABLED !== 'false'
}

export function createLocalSession(username: string): Session {
  const localUserName = username.trim()
  const storageKey = `${LOCAL_USER_KEY_PREFIX}${localUserName.toLowerCase()}`
  let localUserId = localStorage.getItem(storageKey)
  if (!localUserId || !UUID_PATTERN.test(localUserId)) {
    // ponytail: browser-local identity is enough for dev login; move mapping server-side if cross-browser identity matters.
    localUserId = crypto.randomUUID()
    localStorage.setItem(storageKey, localUserId)
  }
  return { localUserId, localUserName }
}

function oidcManager(): UserManager {
  return new UserManager({
    authority: import.meta.env.VITE_OIDC_AUTHORITY,
    client_id: import.meta.env.VITE_OIDC_CLIENT_ID,
    redirect_uri: import.meta.env.VITE_OIDC_REDIRECT_URI || `${window.location.origin}/auth/callback`,
    post_logout_redirect_uri: window.location.origin,
    response_type: 'code',
    scope: import.meta.env.VITE_OIDC_SCOPE || 'openid profile email',
    userStore: new WebStorageStateStore({ store: window.sessionStorage }),
  })
}

export async function startOidcLogin(): Promise<void> {
  await oidcManager().signinRedirect()
}

export async function finishOidcLogin(): Promise<Session> {
  const user: OidcUser = await oidcManager().signinRedirectCallback()
  const session = { accessToken: user.access_token }
  saveSession(session)
  return session
}

export async function logoutOidc(): Promise<void> {
  clearSession()
  await oidcManager().signoutRedirect()
}
