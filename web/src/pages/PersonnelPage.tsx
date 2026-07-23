import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto, UserDto } from '../api/types'

export function PersonnelPage() {
  const [people, setPeople] = useState<UserDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('TECHNICIAN')
  const [siteId, setSiteId] = useState('')
  const [password, setPassword] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [userRows, siteRows] = await Promise.all([api.listUsers(), api.listSites()])
      setPeople(userRows)
      setSites(siteRows)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load personnel')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  function siteLabel(ids?: string[]) {
    if (!ids?.length) return '—'
    return ids.map((id) => sites.find((site) => site.id === id)?.name ?? id).join(', ')
  }

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.createUser({
        displayName: displayName.trim(),
        email: email.trim(),
        role,
        assignedSiteIds: role === 'SUPER_ADMIN' ? [] : siteId ? [siteId] : [],
        password: password.length >= 8 ? password : undefined,
      })
      setOpen(false)
      setDisplayName('')
      setEmail('')
      setPassword('')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create user')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Personnel Management</h1>
          <p className="muted">Create and review personnel accounts assigned to units.</p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)}>
          Add personnel
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {people.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Name</th>
                <th>Email</th>
                <th>Role</th>
                <th>Status</th>
                <th>Units</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {people.map((person) => (
                <tr key={person.id}>
                  <td className="cell-title">{person.displayName}</td>
                  <td>{person.email}</td>
                  <td>
                    <span className="badge">{person.role}</span>
                  </td>
                  <td>{person.accountStatus || 'ACTIVE'}</td>
                  <td>{siteLabel(person.assignedSiteIds)}</td>
                  <td className="col-actions">
                    {person.role !== 'SUPER_ADMIN' && (
                      <button
                        className="btn linkish"
                        type="button"
                        onClick={() =>
                          void (async () => {
                            if (!confirm('Move user to Recycle Bin?')) return
                            await api.deleteUser(person.id)
                            await reload()
                          })()
                        }
                      >
                        Recycle
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No personnel loaded.</div>
      )}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onCreate}>
            <h2>Add personnel</h2>
            <p className="dialog-copy">Create a new operator or admin account for the web and terminal ecosystem.</p>
            <div className="field">
              <label>Display name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Email</label>
              <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div className="split">
              <div className="field">
                <label>Role</label>
                <select value={role} onChange={(e) => setRole(e.target.value)}>
                  <option value="TECHNICIAN">TECHNICIAN</option>
                  <option value="VENDOR">VENDOR</option>
                  <option value="SUPER_ADMIN">SUPER_ADMIN</option>
                </select>
              </div>
              {role !== 'SUPER_ADMIN' && (
                <div className="field">
                  <label>Assigned unit</label>
                  <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                    {sites.map((site) => (
                      <option key={site.id} value={site.id}>
                        {site.name}
                      </option>
                    ))}
                  </select>
                </div>
              )}
            </div>
            <div className="field">
              <label>Initial password (min 8, optional)</label>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                minLength={8}
              />
            </div>
            <div className="dialog-actions">
              <button className="btn secondary" type="button" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                Save
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
