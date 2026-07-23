import { useEffect, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto, TerminalDto } from '../api/types'

export function TerminalsPage() {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [siteFilter, setSiteFilter] = useState('all')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [siteId, setSiteId] = useState('')
  const [deviceId, setDeviceId] = useState('')
  const [nodeCount, setNodeCount] = useState('24')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [siteRows, terminalRows] = await Promise.all([api.listSites(), api.listTerminals()])
      setSites(siteRows)
      setTerminals(terminalRows)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load terminals')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const visible = siteFilter === 'all' ? terminals : terminals.filter((terminal) => terminal.siteId === siteFilter)

  function siteName(id: string) {
    return sites.find((site) => site.id === id)?.name ?? 'Unassigned unit'
  }

  async function onCreate(e: FormEvent) {
    e.preventDefault()
    if (!siteId) {
      setError('Select a unit before saving a terminal.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.createTerminal({
        siteId,
        name: name.trim() || 'New Cabinet',
        boxAddress: 1,
        serialNumber: deviceId.trim() || null,
        configuredSlotCount: Math.min(255, Math.max(1, Number(nodeCount) || 24)),
      })
      setOpen(false)
      setName('')
      setDeviceId('')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create terminal')
    } finally {
      setBusy(false)
    }
  }

  async function onArchive(id: string) {
    if (!confirm('Move this terminal to the Recycle Bin?')) return
    setBusy(true)
    setError(null)
    try {
      await api.deleteTerminal(id)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to archive terminal')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Terminal Settings</h1>
          <p className="muted">
            Register Android key-cabinet terminals. Physical serial I/O remains on the Terminal only.
          </p>
        </div>
        <button className="btn" type="button" onClick={() => setOpen(true)} disabled={!sites.length}>
          Add Android terminal
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="field" style={{ maxWidth: 340 }}>
        <label>Filter by unit</label>
        <select value={siteFilter} onChange={(e) => setSiteFilter(e.target.value)}>
          <option value="all">All units</option>
          {sites.map((site) => (
            <option key={site.id} value={site.id}>
              {site.name}
            </option>
          ))}
        </select>
      </div>

      {visible.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Terminal</th>
                <th>Unit</th>
                <th>Device ID</th>
                <th>Config</th>
                <th>Key Cabinet ID</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((terminal) => (
                <tr key={terminal.id}>
                  <td className="cell-title">{terminal.name}</td>
                  <td>{siteName(terminal.siteId)}</td>
                  <td>{terminal.serialNumber || '—'}</td>
                  <td>
                    Box {terminal.boxAddress} · {terminal.configuredSlotCount} nodes ·{' '}
                    {terminal.connectionState || 'UNKNOWN'}
                  </td>
                  <td>{terminal.id}</td>
                  <td className="col-actions">
                    <button
                      className="btn linkish"
                      type="button"
                      disabled={busy}
                      onClick={() => void onArchive(terminal.id)}
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
        !busy && <div className="empty-state">No terminals yet.</div>
      )}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onCreate}>
            <h2>Add Android terminal</h2>
            <p className="dialog-copy">Create a cabinet record for the website and Android Terminal pairing.</p>
            <div className="field">
              <label>Terminal / cabinet name</label>
              <input value={name} onChange={(e) => setName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Affiliated unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="split">
              <div className="field">
                <label>Unique device ID</label>
                <input value={deviceId} onChange={(e) => setDeviceId(e.target.value)} />
              </div>
              <div className="field">
                <label>Configured node count</label>
                <input value={nodeCount} onChange={(e) => setNodeCount(e.target.value)} />
              </div>
            </div>
            <div className="dialog-actions">
              <button className="btn secondary" type="button" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                Save terminal
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
