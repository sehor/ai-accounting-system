import { createContext, useContext, useMemo, useState, type PropsWithChildren } from 'react'
import { clearSession, getSession, saveSession, type Session } from './session'

interface AuthContextValue {
  session: Session | null
  signIn: (session: Session) => void
  signOut: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: PropsWithChildren) {
  const [session, setSession] = useState<Session | null>(() => getSession())
  const value = useMemo<AuthContextValue>(() => ({
    session,
    signIn: (next) => { saveSession(next); setSession(next) },
    signOut: () => { clearSession(); setSession(null) },
  }), [session])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext)
  if (!context) throw new Error('useAuth must be used within AuthProvider')
  return context
}
