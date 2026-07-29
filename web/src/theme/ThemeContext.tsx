import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'

export type ThemeMode = 'system' | 'light' | 'dark'

const STORAGE_KEY = 'ekms_web_theme'

function systemPrefersDark() {
  return window.matchMedia('(prefers-color-scheme: dark)').matches
}

function loadStoredMode(): ThemeMode {
  try {
    const stored = localStorage.getItem(STORAGE_KEY)
    return stored === 'light' || stored === 'dark' ? stored : 'system'
  } catch {
    return 'system'
  }
}

function resolveEffective(mode: ThemeMode): 'light' | 'dark' {
  return mode === 'dark' || (mode === 'system' && systemPrefersDark()) ? 'dark' : 'light'
}

function applyTheme(mode: ThemeMode) {
  document.documentElement.setAttribute('data-theme', resolveEffective(mode))
}

type ThemeState = {
  mode: ThemeMode
  effective: 'light' | 'dark'
  setMode: (mode: ThemeMode) => void
}

const ThemeContext = createContext<ThemeState | null>(null)

/**
 * Mirrors terminalApp's TerminalThemeMode (SYSTEM/LIGHT/DARK, SYSTEM = follow the OS,
 * explicit choice overrides). localStorage is this app's equivalent of terminalApp's
 * SharedPreferences — device/browser-local, never backend-synced, same footing as any
 * other client-only display preference.
 */
export function ThemeProvider({ children }: { children: ReactNode }) {
  const [mode, setModeState] = useState<ThemeMode>(() => loadStoredMode())
  const [effective, setEffective] = useState<'light' | 'dark'>(() => resolveEffective(mode))

  const setMode = useCallback((next: ThemeMode) => {
    setModeState(next)
    try {
      localStorage.setItem(STORAGE_KEY, next)
    } catch {
      /* localStorage unavailable (private mode, etc.) — mode still applies for this session */
    }
  }, [])

  useEffect(() => {
    applyTheme(mode)
    setEffective(resolveEffective(mode))

    if (mode !== 'system') return
    const query = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => {
      applyTheme('system')
      setEffective(resolveEffective('system'))
    }
    query.addEventListener('change', onChange)
    return () => query.removeEventListener('change', onChange)
  }, [mode])

  const value = useMemo(() => ({ mode, effective, setMode }), [mode, effective, setMode])
  return <ThemeContext.Provider value={value}>{children}</ThemeContext.Provider>
}

export function useTheme() {
  const ctx = useContext(ThemeContext)
  if (!ctx) throw new Error('useTheme outside ThemeProvider')
  return ctx
}
