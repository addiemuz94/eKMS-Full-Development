import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto, UserDto } from '../api/types'

export function PermissionsPage() {
  const [grants, setGrants] = useState<Record<string, unknown>[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
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
        setError(
          'This grant was changed by someone else since you opened it. Reloading the latest version — please reapply your edit.',
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
    <section>
      <div className="page-header">
        <div>
          <h1>Permission Settings</h1>
          <p className="muted">Bind exact keys to a person. A site-only assignment is never enough.</p>
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => {
            setEditingGrant(null)
            setOpen(true)
          }}
        >
          Add access grant
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {grants.map((grant) => (
        <article className="card" key={String(grant.id)}>
          <h3>{users.find((user) => user.id === grant.userId)?.displayName ?? String(grant.userId)}</h3>
          <div className="meta">
            <div>Unit: {sites.find((site) => site.id === grant.siteId)?.name ?? String(grant.siteId)}</div>
            <div>
              Keys:{' '}
              {Array.isArray(grant.keyIds)
                ? (grant.keyIds as string[])
                    .map((id) => keys.find((key) => key.id === id)?.displayName ?? id)
                    .join(', ')
                : '—'}
            </div>
          </div>
          <div className="card-actions">
            <button className="btn linkish" type="button" onClick={() => openEdit(grant)}>
              Edit
            </button>
            <button
              className="btn linkish"
              type="button"
              onClick={() =>
                void (async () => {
                  if (!confirm('Move grant to Recycle Bin?')) return
                  await api.deleteAccessGrant(String(grant.id))
                  await reload()
                })()
              }
            >
              Move to Recycle Bin
            </button>
          </div>
        </article>
      ))}

      {!grants.length && !busy && <div className="empty-state">No access grants yet.</div>}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onSave}>
            <h2>{editingGrant ? 'Edit access grant' : 'Add access grant'}</h2>
            <p className="dialog-copy">Grant a named person permission to a specific key under a specific unit.</p>
            <div className="field">
              <label>Personnel</label>
              <select value={userId} onChange={(e) => setUserId(e.target.value)} required>
                {users.map((user) => (
                  <option key={user.id} value={user.id}>
                    {user.displayName}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>Exact key</label>
              <select value={keyId} onChange={(e) => setKeyId(e.target.value)} required>
                {keys.map((key) => (
                  <option key={key.id} value={key.id}>
                    {key.displayName}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <button
                className="btn secondary"
                type="button"
                onClick={() => {
                  setOpen(false)
                  setEditingGrant(null)
                }}
              >
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                {editingGrant ? 'Save changes' : 'Save'}
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
