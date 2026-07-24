import { useState, type FormEvent } from 'react'
import { Navigate } from 'react-router-dom'
import { ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Button } from '../components/ui'

export function LoginPage() {
  const { session, login } = useAuth()
  const [company, setCompany] = useState('Cavotec Malaysia')
  const [email, setEmail] = useState('admin@kms-cvt.com')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (session) return <Navigate to="/" replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    if (!company.trim() || !email.trim() || !password) {
      setError('Enter company, account and password to continue.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await login(email, password)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Unable to sign in.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="login-wrap">
      <form className="login-card" onSubmit={onSubmit}>
        <div className="login-brand-row">
          <div className="brand-mark">EK</div>
          <div>
            <h1>eKMS</h1>
            <p className="muted" style={{ margin: '2px 0 0' }}>
              Cavotec key management
            </p>
          </div>
        </div>
        <p className="muted">Sign in to the website management portal.</p>

        <div className="field">
          <label>Company / organisation</label>
          <input value={company} onChange={(e) => setCompany(e.target.value)} />
        </div>

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

        {error && <div className="error-banner">{error}</div>}

        <Button type="submit" loading={busy} style={{ width: '100%' }}>
          {busy ? 'Signing in…' : 'Sign in'}
        </Button>
      </form>
    </div>
  )
}
