import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { CredentialStatusDto, SiteDto, TerminalDto, UserDto } from '../api/types'

function nfcStatusLabel(status: string | undefined): string {
  switch (status) {
    case 'ACTIVE':
      return 'Active'
    case 'PENDING_TERMINAL_ENROLLMENT':
      return 'Pending terminal'
    case 'NOT_ASSIGNED':
      return 'Not assigned'
    default:
      return status ? status.replaceAll('_', ' ').toLowerCase() : 'Not assigned'
  }
}

export function PersonnelPage() {
  const [people, setPeople] = useState<UserDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [cardStatusByUser, setCardStatusByUser] = useState<Record<string, CredentialStatusDto | null>>(
    {},
  )
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [role, setRole] = useState('TECHNICIAN')
  const [siteId, setSiteId] = useState('')
  const [password, setPassword] = useState('')

  const loadCardStatuses = useCallback(async (userRows: UserDto[]) => {
    const entries = await Promise.all(
      userRows.map(async (person) => {
        try {
          const creds = await api.listUserCredentials(person.id)
          const nfc = creds.find((c) => c.credentialKind === 'NFC_CARD') ?? null
          return [person.id, nfc] as const
        } catch {
          return [person.id, null] as const
        }
      }),
    )
    setCardStatusByUser(Object.fromEntries(entries))
  }, [])

  const reload = useCallback(async () => {
    setBusy(true)
    setError(null)
    try {
      const [userRows, siteRows, terminalRows] = await Promise.all([
        api.listUsers(),
        api.listSites(),
        api.listTerminals(),
      ])
      setPeople(userRows)
      setSites(siteRows)
      setTerminals(terminalRows)
      setSiteId((current) => current || siteRows[0]?.id || '')
      // #region agent log
      fetch('/v1/debug/agent-log', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-Debug-Session-Id': '5c6d1f' },
        body: JSON.stringify({
          sessionId: '5c6d1f',
          hypothesisId: 'C',
          location: 'PersonnelPage.tsx:reload',
          message: 'web personnel list loaded',
          data: {
            count: userRows.length,
            emails: userRows.map((u) => u.email).slice(0, 20),
          },
          timestamp: Date.now(),
          runId: 'pre-fix',
        }),
      }).catch(() => {})
      // #endregion
      await loadCardStatuses(userRows)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load personnel')
    } finally {
      setBusy(false)
    }
  }, [loadCardStatuses])

  useEffect(() => {
    void reload()
  }, [reload])

  useEffect(() => {
    const onFocus = () => {
      void reload()
    }
    window.addEventListener('focus', onFocus)
    return () => window.removeEventListener('focus', onFocus)
  }, [reload])

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
      setError(err instanceof ApiError ? err.message : 'Failed to create personnel')
    } finally {
      setBusy(false)
    }
  }

  async function requestNfcEnrollment(person: UserDto) {
    setBusy(true)
    setError(null)
    try {
      const unitId = person.assignedSiteIds?.[0]
      const terminalId =
        terminals.find((t) => t.siteId === unitId)?.id ?? terminals[0]?.id ?? null
      await api.requestCredentialEnrollment(person.id, {
        credentialKind: 'NFC_CARD',
        terminalId,
        note: 'Requested from Website',
      })
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to request card enrollment')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Personnel Management</h1>
          <p className="muted">
            Create and review personnel accounts assigned to units. Card enrollment is completed on
            the terminal.
          </p>
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
                <th>Card enrollment</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {people.map((person) => {
                const card = cardStatusByUser[person.id]
                return (
                  <tr key={person.id}>
                    <td className="cell-title">{person.displayName}</td>
                    <td>{person.email}</td>
                    <td>
                      <span className="badge">{person.role}</span>
                    </td>
                    <td>{person.accountStatus || 'ACTIVE'}</td>
                    <td>{siteLabel(person.assignedSiteIds)}</td>
                    <td>
                      <span className="badge">{nfcStatusLabel(card?.enrollmentStatus)}</span>
                    </td>
                    <td className="col-actions">
                      {person.role !== 'SUPER_ADMIN' && (
                        <>
                          <button
                            className="btn linkish"
                            type="button"
                            disabled={busy || card?.enrollmentStatus === 'ACTIVE'}
                            onClick={() => void requestNfcEnrollment(person)}
                          >
                            Request NFC enrollment
                          </button>
                          <button
                            className="btn linkish"
                            type="button"
                            onClick={() =>
                              void (async () => {
                                if (!confirm('Move personnel to Recycle Bin?')) return
                                await api.deleteUser(person.id)
                                await reload()
                              })()
                            }
                          >
                            Recycle
                          </button>
                        </>
                      )}
                    </td>
                  </tr>
                )
              })}
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
            <p className="dialog-copy">
              Create a new operator or admin account for the web and terminal ecosystem.
            </p>
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
