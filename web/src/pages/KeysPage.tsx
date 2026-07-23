import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto } from '../api/types'

export function KeysPage() {
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [displayName, setDisplayName] = useState('')
  const [siteId, setSiteId] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [keyRows, siteRows] = await Promise.all([api.listKeys(), api.listSites()])
      setKeys(keyRows)
      setSites(siteRows)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load keys')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.createKey({ siteId, displayName: displayName.trim() })
      setOpen(false)
      setDisplayName('')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create key')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Key Settings</h1>
          <p className="muted">Managed keys from the backend. Raw NFC UIDs never appear here.</p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)} disabled={!sites.length}>
          Add key
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {keys.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Unit</th>
                <th>Enrollment</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {keys.map((key) => (
                <tr key={key.id}>
                  <td className="cell-title">{key.displayName}</td>
                  <td>{sites.find((site) => site.id === key.siteId)?.name ?? '—'}</td>
                  <td>{key.fobEnrollmentReference || 'Not enrolled'}</td>
                  <td className="col-actions">
                    <button
                      className="btn linkish"
                      type="button"
                      onClick={() =>
                        void (async () => {
                          if (!confirm('Move key to Recycle Bin?')) return
                          await api.deleteKey(key.id)
                          await reload()
                        })()
                      }
                    >
                      Recycle
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No keys loaded.</div>
      )}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onCreate}>
            <h2>Add key</h2>
            <p className="dialog-copy">Create a managed key record without exposing NFC secrets or biometric material.</p>
            <div className="field">
              <label>Key name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
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
