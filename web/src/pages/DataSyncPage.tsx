import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { TerminalDto } from '../api/types'
import { Button, LinearProgress, SegmentedControl } from '../components/ui'

type SyncPanel = 'conflicts' | 'terminals'

export function DataSyncPage() {
  const [searchParams] = useSearchParams()
  const terminalFilter = searchParams.get('terminalId')

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

  useEffect(() => {
    if (terminalFilter) setPanel('conflicts')
  }, [terminalFilter])

  const activeTerminalIds = useMemo(() => new Set(terminals.map((t) => t.id)), [terminals])

  const visibleConflicts = useMemo(() => {
    const activeOnly = conflicts.filter((c) => {
      const tid = c.terminalId == null ? null : String(c.terminalId)
      // Drop conflicts for soft-deleted cabinets (history lives under Activity archive / Deleted items).
      if (tid && !activeTerminalIds.has(tid)) return false
      if (terminalFilter && tid !== terminalFilter) return false
      return true
    })
    return activeOnly
  }, [conflicts, terminalFilter, activeTerminalIds])

  const filteredTerminal = useMemo(
    () => (terminalFilter ? terminals.find((t) => t.id === terminalFilter) ?? null : null),
    [terminals, terminalFilter],
  )

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
            Review offline cabinet conflicts. Never silently overwrite — choose Keep Server, Keep Cabinet
            Change, or merge later.
            {filteredTerminal ? ` Filtered to ${filteredTerminal.name}.` : ''}
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
          { value: 'terminals', label: 'Cabinets' },
        ]}
      />

      {panel === 'terminals' && (
        <div className="card">
          <h3>Registered cabinets</h3>
          <div className="meta">
            {(terminalFilter ? terminals.filter((t) => t.id === terminalFilter) : terminals).map(
              (terminal) => (
                <div key={terminal.id}>
                  {terminal.name} · Key Cabinet ID:{' '}
                  <span className="mono">{terminal.id}</span>
                </div>
              ),
            )}
            {!terminals.length && <div>No cabinets registered yet.</div>}
          </div>
        </div>
      )}

      {panel === 'conflicts' &&
        visibleConflicts.map((conflict) => (
          <article className="card" key={String(conflict.id)}>
            <h3>
              {String(conflict.entityType)} · <span className="mono">{String(conflict.entityId)}</span>
            </h3>
            <div className="meta">
              <div>
                Cabinet: <span className="mono">{String(conflict.terminalId)}</span>
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
                Keep cabinet change
              </Button>
              <Button variant="outlined" disabled>
                Merge manually
              </Button>
            </div>
          </article>
        ))}

      {panel === 'conflicts' && !visibleConflicts.length && !busy && (
        <div className="empty-state">
          {terminalFilter ? 'No open sync conflicts for this cabinet.' : 'No open sync conflicts.'}
        </div>
      )}
    </section>
  )
}
