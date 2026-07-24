import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { TerminalDto } from '../api/types'
import { Button, LinearProgress, SegmentedControl } from '../components/ui'

type SyncPanel = 'conflicts' | 'terminals'

export function DataSyncPage() {
  const [conflicts, setConflicts] = useState<Record<string, unknown>[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [panel, setPanel] = useState<SyncPanel>('conflicts')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [conflictRows, terminalRows] = await Promise.all([api.listSyncConflicts(), api.listTerminals()])
      setConflicts(conflictRows)
      setTerminals(terminalRows)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load sync data')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  async function resolve(id: string, strategy: string) {
    setBusy(true)
    try {
      await api.resolveSyncConflict(id, { strategy })
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Resolve failed')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="stack">
      <div className="page-header">
        <div>
          <h1>Data Synchronization</h1>
          <p className="muted">
            Review offline Terminal conflicts. Never silently overwrite — choose Keep Server, Keep Terminal
            Change, or merge later.
          </p>
        </div>
        <Button variant="tonal" loading={busy} onClick={() => void reload()}>
          Refresh
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Sync in progress" />}

      <SegmentedControl
        ariaLabel="Sync panel"
        value={panel}
        onChange={setPanel}
        options={[
          { value: 'conflicts', label: 'Conflicts' },
          { value: 'terminals', label: 'Terminals' },
        ]}
      />

      {panel === 'terminals' && (
        <div className="card">
          <h3>Registered terminals</h3>
          <div className="meta">
            {terminals.map((terminal) => (
              <div key={terminal.id}>
                {terminal.name} · Key Cabinet ID:{' '}
                <span className="mono">{terminal.id}</span>
              </div>
            ))}
            {!terminals.length && <div>No terminals registered yet.</div>}
          </div>
        </div>
      )}

      {panel === 'conflicts' &&
        conflicts.map((conflict) => (
          <article className="card" key={String(conflict.id)}>
            <h3>
              {String(conflict.entityType)} · <span className="mono">{String(conflict.entityId)}</span>
            </h3>
            <div className="meta">
              <div>
                Terminal: <span className="mono">{String(conflict.terminalId)}</span>
              </div>
              <div>Server revision: {String(conflict.serverRevision)}</div>
              <div>
                Local payload:{' '}
                {String((conflict.localChange as { payloadJson?: string } | undefined)?.payloadJson ?? '—')}
              </div>
            </div>
            <div className="card-actions">
              <Button loading={busy} onClick={() => void resolve(String(conflict.id), 'KEEP_SERVER')}>
                Keep server
              </Button>
              <Button
                variant="tonal"
                loading={busy}
                onClick={() => void resolve(String(conflict.id), 'KEEP_TERMINAL_CHANGE')}
              >
                Keep terminal change
              </Button>
              <Button variant="outlined" disabled>
                Merge manually
              </Button>
            </div>
          </article>
        ))}

      {panel === 'conflicts' && !conflicts.length && !busy && (
        <div className="empty-state">No open sync conflicts.</div>
      )}
    </section>
  )
}
