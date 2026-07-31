import { useEffect, useState, type FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { Button, LinearProgress } from '../components/ui'

/**
 * God Admin only — register the first Super Admin for this deployment.
 * Hidden developer bootstrap; unused once a Super Admin exists.
 */
export function BootstrapPage() {
  const { session, logout } = useAuth()
  const navigate = useNavigate()
  const [hasSuperAdmin, setHasSuperAdmin] = useState<boolean | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (session?.role !== 'GOD_ADMIN') {
      navigate('/', { replace: true })
      return
    }
    void (async () => {
      try {
        const status = await api.getBootstrapStatus()
        setHasSuperAdmin(status.hasSuperAdmin)
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load bootstrap status')
        setHasSuperAdmin(false)
      }
    })()
  }, [session, navigate])

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      await api.createUser({
        displayName: displayName.trim(),
        email: email.trim(),
        role: 'SUPER_ADMIN',
        password,
        assignedSiteIds: [],
      })
      setNotice('First Super Admin created. Sign out and sign in as that Super Admin to continue.')
      setHasSuperAdmin(true)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Could not create Super Admin')
    } finally {
      setBusy(false)
    }
  }

  if (session?.role !== 'GOD_ADMIN') return null

  return (
    <section className="stack" style={{ maxWidth: 480, margin: '2rem auto' }}>
      <div className="page-header">
        <div>
          <h1>Bootstrap</h1>
          <p className="muted">
            God Admin (developer mode) — register the first Super Admin for this deployment. This
            account is hidden from all personnel lists.
          </p>
        </div>
        <Button type="button" variant="outlined" onClick={() => logout()}>
          Sign out
        </Button>
      </div>

      {busy && <LinearProgress />}
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice">{notice}</div>}

      {hasSuperAdmin === true ? (
        <div className="empty-state">
          Bootstrap complete. A Super Admin already exists. Sign out and use that account for
          day-to-day administration.
        </div>
      ) : (
        <form className="stack" onSubmit={(e) => void onSubmit(e)}>
          <label className="field">
            <span>Display name</span>
            <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
          </label>
          <label className="field">
            <span>Email</span>
            <input
              type="email"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </label>
          <label className="field">
            <span>Password (min 8)</span>
            <input
              type="password"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
              minLength={8}
              required
            />
          </label>
          <Button type="submit" disabled={busy}>
            Register first Super Admin
          </Button>
        </form>
      )}
    </section>
  )
}
