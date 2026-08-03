import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto, UserDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from './ui'

type Props = {
  /** When set, only grants for this location are shown and create locks the location. */
  lockedSiteId?: string
  /** Hide page chrome when embedded in Cabinet Management. */
  embedded?: boolean
  /** Prefer these users in the personnel picker (e.g. location assignees). */
  preferredUserIds?: string[]
}

export function AccessGrantsPanel({ lockedSiteId, embedded = false, preferredUserIds }: Props) {
  const { confirmAction, dialog } = useConfirm()
  const [grants, setGrants] = useState<Record<string, unknown>[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState(lockedSiteId ?? 'all')
  const [userFilter, setUserFilter] = useState('all')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingGrant, setEditingGrant] = useState<Record<string, unknown> | null>(null)
  const [userId, setUserId] = useState('')
  const [siteId, setSiteId] = useState(lockedSiteId ?? '')
  const [keyId, setKeyId] = useState('')

  useEffect(() => {
    if (lockedSiteId) {
      setSiteFilter(lockedSiteId)
      setSiteId(lockedSiteId)
    }
  }, [lockedSiteId])

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
      const defaultSite = lockedSiteId || siteRows[0]?.id || ''
      if (!siteId && defaultSite) setSiteId(defaultSite)
      const siteKeys = lockedSiteId
        ? keyRows.filter((k) => k.siteId === lockedSiteId)
        : keyRows
      if (!keyId && siteKeys[0]) setKeyId(siteKeys[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load permissions')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [lockedSiteId])

  const keysForForm = useMemo(() => {
    const sid = lockedSiteId || siteId
    if (!sid) return keys
    return keys.filter((k) => k.siteId === sid)
  }, [keys, lockedSiteId, siteId])

  const usersForForm = useMemo(() => {
    if (!preferredUserIds?.length) return users
    const preferred = new Set(preferredUserIds)
    const first = users.filter((u) => preferred.has(u.id))
    const rest = users.filter((u) => !preferred.has(u.id))
    return [...first, ...rest]
  }, [users, preferredUserIds])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    const effectiveSite = lockedSiteId ?? siteFilter
    return grants
      .filter((g) => {
        const uname = users.find((u) => u.id === g.userId)?.displayName ?? ''
        const sname = sites.find((s) => s.id === g.siteId)?.name ?? ''
        const knames = (Array.isArray(g.keyIds) ? (g.keyIds as string[]) : [])
          .map((id) => keys.find((k) => k.id === id)?.displayName ?? '')
          .join(' ')
        const matchQ =
          !q ||
          uname.toLowerCase().includes(q) ||
          sname.toLowerCase().includes(q) ||
          knames.toLowerCase().includes(q)
        const matchSite = effectiveSite === 'all' || g.siteId === effectiveSite
        const matchUser = userFilter === 'all' || g.userId === userFilter
        return matchQ && matchSite && matchUser
      })
      .sort((a, b) => {
        const an = users.find((u) => u.id === a.userId)?.displayName ?? ''
        const bn = users.find((u) => u.id === b.userId)?.displayName ?? ''
        return an.localeCompare(bn)
      })
  }, [grants, users, keys, sites, query, siteFilter, userFilter, lockedSiteId])

  function openEdit(grant: Record<string, unknown>) {
    setEditingGrant(grant)
    setUserId(String(grant.userId ?? ''))
    setSiteId(lockedSiteId || String(grant.siteId ?? ''))
    const grantKeyIds = Array.isArray(grant.keyIds) ? (grant.keyIds as string[]) : []
    setKeyId(grantKeyIds[0] ?? '')
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    const effectiveSite = lockedSiteId || siteId
    if (!userId || !effectiveSite || !keyId) {
      setError('Select personnel, location and at least one key.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      if (editingGrant) {
        await api.updateAccessGrant(String(editingGrant.id), {
          userId,
          siteId: effectiveSite,
          keyIds: [keyId],
          validFromEpochMillis: editingGrant.validFromEpochMillis ?? null,
          validUntilEpochMillis: editingGrant.validUntilEpochMillis ?? null,
          expectedRevision: Number(editingGrant.revision ?? 0),
        })
      } else {
        await api.createAccessGrant({ userId, siteId: effectiveSite, keyIds: [keyId] })
      }
      setOpen(false)
      setEditingGrant(null)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This grant was changed by someone else since you opened it. Reloading — please reapply.',
        )
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
    <section className={embedded ? 'access-grants-embedded' : undefined}>
      {!embedded && (
        <div className="page-header">
          <div>
            <h1>Key Permission</h1>
            <p className="muted">Bind exact keys to personnel. A location-only assignment is never sufficient.</p>
          </div>
          <Button
            icon={Plus}
            onClick={() => {
              setEditingGrant(null)
              if (lockedSiteId) setSiteId(lockedSiteId)
              setOpen(true)
            }}
          >
            Add key permission
          </Button>
        </div>
      )}

      {embedded && (
        <div className="cabinet-people-section-header">
          <div>
            <h3 style={{ margin: 0 }}>Key Permission</h3>
            <p className="muted" style={{ margin: '4px 0 0' }}>
              Which personnel may pick up keys for this cabinet&apos;s location.
            </p>
          </div>
          <Button
            icon={Plus}
            onClick={() => {
              setEditingGrant(null)
              if (lockedSiteId) setSiteId(lockedSiteId)
              setOpen(true)
            }}
          >
            Add key permission
          </Button>
        </div>
      )}

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading permissions" />}

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search personnel or key…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        {!lockedSiteId && (
          <select
            value={siteFilter}
            onChange={(e) => setSiteFilter(e.target.value)}
            title="Filter by location"
          >
            <option value="all">All locations</option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        )}
        <select
          value={userFilter}
          onChange={(e) => setUserFilter(e.target.value)}
          title="Filter by personnel"
        >
          <option value="all">All personnel</option>
          {users.map((u) => (
            <option key={u.id} value={u.id}>
              {u.displayName}
            </option>
          ))}
        </select>
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Personnel</th>
                {!lockedSiteId && <th>Location</th>}
                <th>Keys</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((grant) => (
                <tr key={String(grant.id)}>
                  <td className="cell-title">
                    {users.find((u) => u.id === grant.userId)?.displayName ?? String(grant.userId)}
                  </td>
                  {!lockedSiteId && (
                    <td>{sites.find((s) => s.id === grant.siteId)?.name ?? String(grant.siteId)}</td>
                  )}
                  <td>
                    {Array.isArray(grant.keyIds)
                      ? (grant.keyIds as string[])
                          .map((id) => keys.find((k) => k.id === id)?.displayName ?? id)
                          .join(', ')
                      : '—'}
                  </td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(grant)}>
                        Edit
                      </Button>
                      <Button
                        variant="link"
                        onClick={() =>
                          void (async () => {
                            if (
                              !(await confirmAction({
                                message:
                                  'Delete this key permission? It will move to Deleted items and can be restored for 60 days.',
                                danger: true,
                              }))
                            )
                              return
                            await api.deleteAccessGrant(String(grant.id))
                            await reload()
                          })()
                        }
                      >
                        Delete
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No key permissions match current filters.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingGrant ? 'Edit key permission' : 'Add key permission'}</h2>
            <p className="dialog-copy">
              Grant permission for personnel to pick up a specific key at a specific location.
            </p>
            <div className="field">
              <label>Personnel</label>
              <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
                {usersForForm.map((u) => (
                  <option key={u.id} value={u.id}>
                    {u.displayName}
                  </option>
                ))}
              </select>
            </div>
            {!lockedSiteId && (
              <div className="field">
                <label>Location</label>
                <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                  {sites.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </div>
            )}
            <div className="field">
              <label>Exact key</label>
              <select value={keyId} onChange={(e) => setKeyId(e.target.value)} required>
                {keysForForm.map((k) => (
                  <option key={k.id} value={k.id}>
                    {k.displayName}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingGrant(null)
                }}
              >
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                {editingGrant ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
