import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { useNavigate } from 'react-router-dom'
import {
  api,
  loadSession,
  setAccessToken,
  setOnSessionExpired,
  setOnSessionRefreshed,
  type Session,
} from '../api/client'

type AuthState = {
  session: Session | null
  login: (email: string, password: string) => Promise<void>
  logout: () => void
}

const AuthContext = createContext<AuthState | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const navigate = useNavigate()
  const [session, setSession] = useState<Session | null>(() => {
    const existing = loadSession()
    if (existing) setAccessToken(existing.accessToken)
    return existing
  })

  useEffect(() => {
    setOnSessionExpired(() => {
      setSession(null)
      navigate('/login?reason=expired', { replace: true })
    })
    setOnSessionRefreshed((next) => {
      setSession(next)
    })
    return () => {
      setOnSessionExpired(null)
      setOnSessionRefreshed(null)
    }
  }, [navigate])

  const login = useCallback(async (email: string, password: string) => {
    const res = await api.login(email, password)
    const next: Session = {
      accessToken: res.accessToken,
      refreshToken: res.refreshToken,
      userId: res.profile.id,
      displayName: res.profile.displayName,
      email: res.profile.email,
      role: res.profile.role || res.role || '',
    }
    setSession(next)
  }, [])

  const logout = useCallback(() => {
    api.logout()
    setSession(null)
  }, [])

  const value = useMemo(() => ({ session, login, logout }), [session, login, logout])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
