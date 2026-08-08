import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { api } from '../api/client'
import { useAuth } from '../auth/AuthContext'

type CapabilitiesState = {
  /** Enabled capability keys for the signed-in user. Null while loading / signed out. */
  capabilities: string[] | null
  loading: boolean
  hasCapability: (key: string) => boolean
  reload: () => Promise<void>
}

const CapabilitiesContext = createContext<CapabilitiesState | null>(null)

export function CapabilitiesProvider({ children }: { children: ReactNode }) {
  const { session } = useAuth()
  const [capabilities, setCapabilities] = useState<string[] | null>(null)
  const [loading, setLoading] = useState(false)

  const reload = useCallback(async () => {
    if (!session) {
      setCapabilities(null)
      return
    }
    if (session.role === 'SUPER_ADMIN') {
      // SA is unrestricted; avoid depending on the me-endpoint for nav.
      setCapabilities(['*'])
      return
    }
    setLoading(true)
    try {
      const me = await api.getMyRoleCapabilities()
      setCapabilities(me.capabilities ?? [])
    } catch {
      // Fail closed for non-SA: hide gated extras rather than showing everything.
      setCapabilities([])
    } finally {
      setLoading(false)
    }
  }, [session])

  useEffect(() => {
    void reload()
  }, [reload])

  const hasCapability = useCallback(
    (key: string) => {
      if (!session) return false
      if (session.role === 'SUPER_ADMIN') return true
      if (!capabilities) return false
      return capabilities.includes(key)
    },
    [session, capabilities],
  )

  const value = useMemo(
    () => ({ capabilities, loading, hasCapability, reload }),
    [capabilities, loading, hasCapability, reload],
  )

  return <CapabilitiesContext.Provider value={value}>{children}</CapabilitiesContext.Provider>
}

export function useCapabilities() {
  const ctx = useContext(CapabilitiesContext)
  if (!ctx) throw new Error('useCapabilities outside CapabilitiesProvider')
  return ctx
}
