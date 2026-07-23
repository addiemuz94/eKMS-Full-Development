import { useEffect, useState } from 'react'
import { api, ApiError } from '../api/client'

type LogLoader = () => Promise<Record<string, unknown>[]>

function LogsPage({
  title,
  description,
  load,
}: {
  title: string
  description: string
  load: LogLoader
}) {
  const [items, setItems] = useState<Record<string, unknown>[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setItems(await load())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load logs')
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
          <h1>{title}</h1>
          <p className="muted">{description}</p>
        </div>
        <button className="btn secondary" type="button" onClick={() => void reload()} disabled={busy}>
          Refresh
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {items.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Event</th>
                <th>When</th>
                <th>Actor</th>
                <th>Detail</th>
                <th>Entity</th>
              </tr>
            </thead>
            <tbody>
              {items.map((item, idx) => (
                <tr key={String(item.id ?? idx)}>
                  <td className="cell-title">{String(item.eventType ?? item.action ?? 'Event')}</td>
                  <td>
                    {item.occurredAtEpochMillis || item.createdAtEpochMillis
                      ? new Date(Number(item.occurredAtEpochMillis ?? item.createdAtEpochMillis)).toLocaleString()
                      : '—'}
                  </td>
                  <td>{String(item.actorUserId ?? '—')}</td>
                  <td>{String(item.detail ?? '—')}</td>
                  <td>
                    {String(item.entityType ?? '—')} {String(item.entityId ?? '')}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No log entries.</div>
      )}
    </section>
  )
}

export function KeyRecordsPage() {
  return (
    <LogsPage
      title="Pickup & Return Records"
      description="Key taken and returned audit events from the backend."
      load={api.listKeyOperations}
    />
  )
}

export function OperationLogsPage() {
  return <LogsPage title="Operation Log" description="General operational audit stream." load={api.listAuditEvents} />
}

export function SystemLogsPage() {
  return (
    <LogsPage
      title="System Operation Log"
      description="Login, account, recycle-bin and configuration events."
      load={api.listSystemLogs}
    />
  )
}

export function EquipmentLogsPage() {
  return (
    <LogsPage
      title="Equipment Operation Log"
      description="Hardware-related key and terminal events reported to the server."
      load={api.listEquipmentLogs}
    />
  )
}
