import { useEffect, useState } from 'react'
import { api, ApiError, type RecycleBinEntry } from '../api/client'
import { Button, useConfirm } from '../components/ui'

export function RecycleBinPage() {
  const { confirmAction, dialog } = useConfirm()
  const [entries, setEntries] = useState<RecycleBinEntry[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setEntries(await api.listRecycleBin())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load deleted items')
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
          <h1>Deleted items</h1>
          <p className="muted">
            Super Admin only. Restore items removed in the last 60 days, or permanently delete them
            before expiry. Audit history is retained.
          </p>
        </div>
        <Button variant="tonal" loading={busy} onClick={() => void reload()}>
          Refresh
        </Button>
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
                      <Button
                        loading={busy}
                        onClick={() =>
                          void (async () => {
                            try {
                              await api.restoreRecycleBin({
                                recordType: entry.recordType,
                                recordId: entry.recordId,
                                expectedRevision: entry.restorePayloadVersion,
                              })
                              await reload()
                            } catch (err) {
                              setError(err instanceof ApiError ? err.message : 'Failed to restore record')
                            }
                          })()
                        }
                      >
                        Restore
                      </Button>
                      <Button
                        variant="danger"
                        loading={busy}
                        onClick={() =>
                          void (async () => {
                            if (
                              !(await confirmAction({
                                message: 'Permanently delete this record? This cannot be undone.',
                                danger: true,
                              }))
                            )
                              return
                            try {
                              await api.purgeRecycleBin({
                                recordType: entry.recordType,
                                recordId: entry.recordId,
                              })
                              await reload()
                            } catch (err) {
                              setError(err instanceof ApiError ? err.message : 'Failed to permanently delete record')
                            }
                          })()
                        }
                      >
                        Permanently delete
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No deleted items.</div>
      )}

      {dialog}
    </section>
  )
}
