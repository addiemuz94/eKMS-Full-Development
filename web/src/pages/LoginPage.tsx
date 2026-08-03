import { useEffect, useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Button } from '../components/ui'

const SPLASH_MS = 2000

export function LoginPage() {
  const { session, login } = useAuth()
  const [searchParams] = useSearchParams()
  const loginExpired = searchParams.get('reason') === 'expired'
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [splash, setSplash] = useState(true)
  const [splashLeaving, setSplashLeaving] = useState(false)

  useEffect(() => {
    const leaveAt = window.setTimeout(() => setSplashLeaving(true), SPLASH_MS - 480)
    const doneAt = window.setTimeout(() => setSplash(false), SPLASH_MS)
    return () => {
      window.clearTimeout(leaveAt)
      window.clearTimeout(doneAt)
    }
  }, [])

  if (session) return <Navigate to="/" replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!email.trim() || !password) {
      setError('Enter account email and password to continue.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(email, password)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Sign-in failed.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      {splash && (
        <div
          className={`login-splash${splashLeaving ? ' leaving' : ''}`}
          aria-hidden={splashLeaving}
          role="presentation"
        >
          <div className="login-splash-glow" />
          <div className="login-splash-ring" />
          <img
            className="login-splash-logo"
            src="/cavotec-logo.png"
            alt=""
            width={280}
            height={64}
          />
          <p className="login-splash-tag">Key Management System</p>
        </div>
      )}

      <form
        className={`login-card${splash ? ' login-card-waiting' : ' login-card-enter'}`}
        onSubmit={onSubmit}
        aria-hidden={splash}
      >
        <div className="login-brand-block">
          <img
            className="login-brand-logo"
            src="/cavotec-logo.png"
            alt="Cavotec"
            width={200}
            height={46}
          />
          <p className="login-brand-sub muted">eKMS · Key management portal</p>
        </div>
        <p className="muted">Sign in to the eKMS Super Admin portal.</p>

        <div className="field">
          <label>Account email</label>
          <input value={email} onChange={(e) => setEmail(e.target.value)} autoComplete="username" />
        </div>

        <div className="field">
          <label>Password</label>
          <input
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            autoComplete="current-password"
          />
        </div>

        {loginExpired && !error && (
          <div className="notice" role="status">
            Your session has expired. Sign in to continue.
          </div>
        )}
        {error && <div className="error-banner">{error}</div>}

        <Button type="submit" loading={busy} style={{ width: '100%' }} disabled={splash}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  )
}
