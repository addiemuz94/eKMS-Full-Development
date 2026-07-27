import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto, UserDto } from '../api/types'
import { Button, LinearProgress } from '../components/ui'

export function PermissionsPage() {
  const [grants, setGrants] = useState<Record<string, unknown>[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState('all')
  const [userFilter, setUserFilter] = useState('all')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingGrant, setEditingGrant] = useState<Record<string, unknown> | null>(null)
  const [userId, setUserId] = useState('')
  const [siteId, setSiteId] = useState('')
  const [keyId, setKeyId] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [grantRows, userRows, keyRows, siteRows] = await Promise.all([
        api.listAccessGrants(),
        api.listUsers(),
        api.listKeys(),
        api.listSites(),
      ])
      setGrants(grantRows)
      setUsers(userRows)
      setKeys(keyRows)
      setSites(siteRows)
      if (!userId && userRows[0]) setUserId(userRows[0].id)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
      if (!keyId && keyRows[0]) setKeyId(keyRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load permissions')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return grants
      .filter((g) => {
        const uname = users.find((u) => u.id === g.userId)?.displayName ?? ''
        const sname = sites.find((s) => s.id === g.siteId)?.name ?? ''
        const knames = (Array.isArray(g.keyIds) ? (g.keyIds as string[]) : [])
          .map((id) => keys.find((k) => k.id === id)?.displayName ?? '')
          .join(' ')
        const matchQ = !q || uname.toLowerCase().includes(q) || sname.toLowerCase().includes(q) || knames.toLowerCase().includes(q)
        const matchSite = siteFilter === 'all' || g.siteId === siteFilter
        const matchUser = userFilter === 'all' || g.userId === userFilter
        return matchQ && matchSite && matchUser
      })
      .sort((a, b) => {
        const an = users.find((u) => u.id === a.userId)?.displayName ?? ''
        const bn = users.find((u) => u.id === b.userId)?.displayName ?? ''
        return an.localeCompare(bn)
      })
  }, [grants, users, keys, sites, query, siteFilter, userFilter])

  function openEdit(grant: Record<string, unknown>) {
    setEditingGrant(grant)
    setUserId(String(grant.userId ?? ''))
    setSiteId(String(grant.siteId ?? ''))
    const grantKeyIds = Array.isArray(grant.keyIds) ? (grant.keyIds as string[]) : []
    setKeyId(grantKeyIds[0] ?? '')
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!userId || !siteId || !keyId) {
      setError('Select personnel, unit and at least one key.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      if (editingGrant) {
        await api.updateAccessGrant(String(editingGrant.id), {
          userId,
          siteId,
          keyIds: [keyId],
          validFromEpochMillis: editingGrant.validFromEpochMillis ?? null,
          validUntilEpochMillis: editingGrant.validUntilEpochMillis ?? null,
          expectedRevision: Number(editingGrant.revision ?? 0),
        })
      } else {
        await api.createAccessGrant({ userId, siteId, keyIds: [keyId] })
      }
      setOpen(false)
      setEditingGrant(null)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This grant was changed by someone else since you opened it. Reloading — please reapply.')
        setOpen(false)
        setEditingGrant(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save grant')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Permission Settings</h1>
          <p className="muted">Bind exact keys to a person. A site-only assignment is never enough.</p>
        </div>
        <Button
          onClick={() => {
            setEditingGrant(null)
            setOpen(true)
          }}
        >
          Add access grant
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading permissions" />}

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search personnel, unit or key…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select value={siteFilter} onChange={(e) => setSiteFilter(e.target.value)} title="Filter by unit">
          <option value="all">All units</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>
        <select value={userFilter} onChange={(e) => setUserFilter(e.target.value)} title="Filter by personnel">
          <option value="all">All personnel</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>{u.displayName}</option>
          ))}
        </select>
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Personnel</th>
                <th>Unit</th>
                <th>Keys</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((grant) => (
                <tr key={String(grant.id)}>
                  <td className="cell-title">{users.find((u) => u.id === grant.userId)?.displayName ?? String(grant.userId)}</td>
                  <td>{sites.find((s) => s.id === grant.siteId)?.name ?? String(grant.siteId)}</td>
                  <td>
                    {Array.isArray(grant.keyIds)
                      ? (grant.keyIds as string[])
                          .map((id) => keys.find((k) => k.id === id)?.displayName ?? id)
                          .join(', ')
                      : '—'}
                  </td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(grant)}>Edit</Button>
                      <Button
                        variant="link"
                        onClick={() =>
                          void (async () => {
                            if (!confirm('Move grant to Recycle Bin?')) return
                            await api.deleteAccessGrant(String(grant.id))
                            await reload()
                          })()
                        }
                      >
                        Recycle
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No access grants match current filters.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingGrant ? 'Edit access grant' : 'Add access grant'}</h2>
            <p className="dialog-copy">Grant a named person permission to a specific key under a specific unit.</p>
            <div className="field">
              <label>Personnel</label>
              <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
                {users.map((u) => (
                  <option key={u.id} value={u.id}>{u.displayName}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((s) => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Exact key</label>
              <select value={keyId} onChange={(e) => setKeyId(e.target.value)} required>
                {keys.map((k) => (
                  <option key={k.id} value={k.id}>{k.displayName}</option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" onClick={() => { setOpen(false); setEditingGrant(null) }}>Cancel</Button>
              <Button type="submit" loading={busy}>{editingGrant ? 'Save changes' : 'Save'}</Button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
