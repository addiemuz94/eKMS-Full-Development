import { useEffect, useState } from 'react'
import { api, ApiError, type RecycleBinEntry } from '../api/client'

export function RecycleBinPage() {
  const [entries, setEntries] = useState<RecycleBinEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setEntries(await api.listRecycleBin())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load Recycle Bin')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Recycle Bin</h1>
          <p className="muted">
            Super Admin only. Soft-deleted records can be restored for 60 days or purged earlier. Audit
            history is retained.
          </p>
        </div>
        <button className="btn secondary" type="button" onClick={() => void reload()} disabled={busy}>
          Refresh
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {entries.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Record</th>
                <th>Deleted</th>
                <th>Expires</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((entry) => (
                <tr key={entry.id}>
                  <td className="cell-title">
                    {entry.recordType}: {entry.recordLabel}
                  </td>
                  <td>{new Date(entry.deletedAtEpochMillis).toLocaleString()}</td>
                  <td>{new Date(entry.expiresAtEpochMillis).toLocaleString()}</td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <button
                        className="btn"
                        type="button"
                        onClick={() =>
                          void (async () => {
                            await api.restoreRecycleBin({
                              recordType: entry.recordType,
                              recordId: entry.recordId,
                              expectedRevision: entry.restorePayloadVersion,
                            })
                            await reload()
                          })()
                        }
                      >
                        Restore
                      </button>
                      <button
                        className="btn danger"
                        type="button"
                        onClick={() =>
                          void (async () => {
                            if (!confirm('Permanently purge this record?')) return
                            await api.purgeRecycleBin({
                              recordType: entry.recordType,
                              recordId: entry.recordId,
                            })
                            await reload()
                          })()
                        }
                      >
                        Purge
                      </button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">Recycle Bin is empty.</div>
      )}
    </section>
  )
}
