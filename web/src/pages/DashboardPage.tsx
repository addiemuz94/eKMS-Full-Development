import { useEffect, useMemo, useState } from 'react'
import { Download } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { KeySlotDto, RegionDto, SiteDto, TerminalDto, UserDto } from '../api/types'
import { TerminalsMap } from '../components/TerminalsMap'
import { Button, CircularProgress, MetricSkeleton } from '../components/ui'

type ActivityKind = 'audit' | 'sync' | 'keys'

type ActivityRow = {
  id: string
  when: number
  label: string
  detail: string
}

const SYNC_EVENT_TYPES = new Set([
  'CONFLICT_CREATED',
  'CONFLICT_RESOLVED',
  'TERMINAL_SYNC_PUSHED',
  'TERMINAL_SYNC_PULL',
])

function countUsersByRole(users: UserDto[]) {
  const counts: Record<string, number> = {}
  for (const user of users) {
    const role = user.role || 'UNKNOWN'
    counts[role] = (counts[role] ?? 0) + 1
  }
  return counts
}

function personnelSiteCount(users: UserDto[], siteId: string | null) {
  if (!siteId) return users.length
  return users.filter((u) => (u.assignedSiteIds ?? []).includes(siteId)).length
}

function slottedKeyCount(slots: KeySlotDto[]) {
  return slots.filter((s) => s.managedKeyId).length
}

export function DashboardPage() {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [regions, setRegions] = useState<RegionDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<{ id: string }[]>([])
  const [keySlots, setKeySlots] = useState<KeySlotDto[]>([])
  const [syncConflicts, setSyncConflicts] = useState<Record<string, unknown>[]>([])
  const [auditEvents, setAuditEvents] = useState<Record<string, unknown>[]>([])
  const [keyOps, setKeyOps] = useState<Record<string, unknown>[]>([])

  const [regionFilter, setRegionFilter] = useState('all')
  const [siteFilter, setSiteFilter] = useState('all')
  const [selectedTerminalId, setSelectedTerminalId] = useState<string | null>(null)
  const [activityKind, setActivityKind] = useState<ActivityKind>('audit')

  const [reportSiteId, setReportSiteId] = useState('')
  const [reportBusy, setReportBusy] = useState(false)

  const [error, setError] = useState<string | null>(null)
  const [activityError, setActivityError] = useState<string | null>(null)
  const [loading, setLoading] = useState(true)
  const [activityLoading, setActivityLoading] = useState(false)

  const selectedTerminal = useMemo(
    () => terminals.find((t) => t.id === selectedTerminalId) ?? null,
    [terminals, selectedTerminalId],
  )

  const selectedSiteId = selectedTerminal?.siteId ?? (siteFilter !== 'all' ? siteFilter : null)

  const roleCounts = useMemo(() => countUsersByRole(users), [users])

  useEffect(() => {
    void (async () => {
      setLoading(true)
      setError(null)
      try {
        const results = await Promise.allSettled([
          api.listSites(),
          api.listTerminals(),
          api.listUsers(),
          api.listKeys(),
          api.listKeySlots(),
          api.listRegions(),
          api.listSyncConflicts(),
        ])
        const pick = <T,>(idx: number, fallback: T) =>
          results[idx].status === 'fulfilled' ? (results[idx] as PromiseFulfilledResult<T>).value : fallback

        setSites(pick(0, []))
        setTerminals(pick(1, []))
        setUsers(pick(2, []))
        setKeys(pick(3, []))
        setKeySlots(pick(4, []))
        setRegions(pick(5, []))
        setSyncConflicts(pick(6, []))

        const failed = results.filter((r) => r.status === 'rejected')
        if (failed.length === results.length) {
          const first = failed[0] as PromiseRejectedResult
          throw first.reason
        }
        if (!reportSiteId && results[0].status === 'fulfilled' && results[0].value[0]) {
          setReportSiteId(results[0].value[0].id)
        }
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load dashboard')
      } finally {
        setLoading(false)
      }
    })()
  }, [])

  useEffect(() => {
    void (async () => {
      setActivityLoading(true)
      setActivityError(null)
      try {
        const scope = {
          siteId: selectedSiteId ?? undefined,
          terminalId: selectedTerminalId ?? undefined,
          limit: 80,
        }
        const [auditRows, keyRows] = await Promise.all([
          api.listAuditEvents(scope),
          api.listKeyOperations(scope),
        ])
        setAuditEvents(auditRows as unknown as Record<string, unknown>[])
        setKeyOps(keyRows)
      } catch (err) {
        setActivityError(err instanceof ApiError ? err.message : 'Failed to load activity')
      } finally {
        setActivityLoading(false)
      }
    })()
  }, [selectedSiteId, selectedTerminalId])

  const terminalSlots = useMemo(() => {
    if (!selectedTerminalId) return []
    return keySlots.filter((s) => s.terminalId === selectedTerminalId)
  }, [keySlots, selectedTerminalId])

  const activityRows = useMemo((): ActivityRow[] => {
    let rows: ActivityRow[] = []
    if (activityKind === 'keys') {
      rows = keyOps.map((row, idx) => ({
        id: String(row.id ?? idx),
        when: Number(row.occurredAtEpochMillis ?? 0),
        label: String(row.eventType ?? 'Key event'),
        detail: String(row.detail ?? row.entityId ?? '—'),
      }))
    } else if (activityKind === 'sync') {
      const fromAudit = auditEvents
        .filter((row) => SYNC_EVENT_TYPES.has(String(row.eventType ?? '')))
        .map((row, idx) => ({
          id: String(row.id ?? `audit-${idx}`),
          when: Number(row.occurredAtEpochMillis ?? 0),
          label: String(row.eventType ?? 'Sync event'),
          detail: String(row.detail ?? '—'),
        }))
      const fromConflicts = syncConflicts
        .filter((c) => {
          if (selectedTerminalId && String(c.terminalId) !== selectedTerminalId) return false
          if (selectedSiteId) {
            const terminal = terminals.find((t) => t.id === String(c.terminalId))
            if (terminal && terminal.siteId !== selectedSiteId) return false
          }
          return true
        })
        .map((c, idx) => ({
          id: String(c.id ?? `conflict-${idx}`),
          when: Number(
            (c.localChange as { createdAtEpochMillis?: number } | undefined)?.createdAtEpochMillis ??
              Date.now(),
          ),
          label: 'Sync conflict',
          detail: `${String(c.entityType)} · rev ${String(c.serverRevision)}`,
        }))
      rows = [...fromConflicts, ...fromAudit]
    } else {
      rows = auditEvents
        .filter((row) => !SYNC_EVENT_TYPES.has(String(row.eventType ?? '')))
        .map((row, idx) => ({
          id: String(row.id ?? idx),
          when: Number(row.occurredAtEpochMillis ?? 0),
          label: String(row.eventType ?? 'Audit event'),
          detail: String(row.detail ?? row.entityId ?? '—'),
        }))
    }
    return rows.sort((a, b) => b.when - a.when).slice(0, 10)
  }, [activityKind, auditEvents, keyOps, syncConflicts, selectedTerminalId, selectedSiteId, terminals])

  async function onDownloadReport() {
    if (!reportSiteId) {
      setError('Select a unit for the location report.')
      return
    }
    setReportBusy(true)
    setError(null)
    try {
      const job = await api.createReportExport({
        kind: 'KEY_OPERATIONS',
        format: 'PDF',
        filter: { siteId: reportSiteId, limit: 500 },
      })
      const siteName = sites.find((s) => s.id === reportSiteId)?.name ?? 'location'
      await api.downloadReportExport(job.downloadPath, `key-operations-${siteName}.pdf`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'PDF export failed')
    } finally {
      setReportBusy(false)
    }
  }

  return (
    <section className="stack">
      <div className="page-header">
        <div>
          <h1>Dashboard</h1>
          <p className="muted">
            Terminal-centric overview of sites, personnel, and keys. Physical key actions stay on the
            Android Terminal.
          </p>
        </div>
        {loading && (
          <div className="busy-inline">
            <CircularProgress size={22} />
            Loading…
          </div>
        )}
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="metrics">
        {loading ? (
          <>
            <MetricSkeleton />
            <MetricSkeleton />
            <MetricSkeleton />
            <MetricSkeleton />
          </>
        ) : (
          <>
            <div className="metric">
              <div className="metric-label">Sites</div>
              <strong>{sites.length}</strong>
            </div>
            <div className="metric">
              <div className="metric-label">Terminals</div>
              <strong>{terminals.length}</strong>
            </div>
            <div className="metric metric-wide">
              <div className="metric-label">Personnel by role</div>
              <div className="role-metrics">
                {Object.entries(roleCounts).length ? (
                  Object.entries(roleCounts).map(([role, count]) => (
                    <span key={role} className="role-metric-chip">
                      {role.replaceAll('_', ' ')}: <strong>{count}</strong>
                    </span>
                  ))
                ) : (
                  <strong>0</strong>
                )}
              </div>
            </div>
            <div className="metric">
              <div className="metric-label">Keys</div>
              <strong>{keys.length}</strong>
            </div>
          </>
        )}
      </div>

      <div className="card dashboard-report-card">
        <h2>Location report</h2>
        <p className="muted">
          Download a PDF of key pickup and return records for a unit (who, what, when, where, why, how).
        </p>
        <div className="toolbar-row">
          <select
            value={reportSiteId}
            onChange={(e) => setReportSiteId(e.target.value)}
            title="Unit for PDF export"
          >
            <option value="">Select unit…</option>
            {sites.map((site) => (
              <option key={site.id} value={site.id}>
                {site.name}
              </option>
            ))}
          </select>
          <Button icon={Download} loading={reportBusy} disabled={!reportSiteId} onClick={() => void onDownloadReport()}>
            Download PDF
          </Button>
        </div>
      </div>

      {!loading && (
        <>
          <TerminalsMap
            sites={sites}
            terminals={terminals}
            regions={regions}
            regionFilter={regionFilter}
            siteFilter={siteFilter}
            selectedTerminalId={selectedTerminalId}
            onRegionFilterChange={setRegionFilter}
            onSiteFilterChange={setSiteFilter}
            onSelectTerminal={setSelectedTerminalId}
          />

          {selectedTerminal && (
            <div className="card terminal-summary-card">
              <h2>{selectedTerminal.name}</h2>
              <div className="meta terminal-summary-grid">
                <div>
                  <span className="muted">Key Cabinet ID</span>
                  <div className="mono">{selectedTerminal.id}</div>
                </div>
                <div>
                  <span className="muted">Unit</span>
                  <div>{sites.find((s) => s.id === selectedTerminal.siteId)?.name ?? '—'}</div>
                </div>
                <div>
                  <span className="muted">Keys on slots</span>
                  <div>
                    {slottedKeyCount(terminalSlots)} / {selectedTerminal.configuredSlotCount}
                  </div>
                </div>
                <div>
                  <span className="muted">Personnel at unit</span>
                  <div>{personnelSiteCount(users, selectedTerminal.siteId)}</div>
                </div>
                <div>
                  <span className="muted">Pairing</span>
                  <div>
                    <span className={`badge${selectedTerminal.paired ? ' badge-success' : ''}`}>
                      {selectedTerminal.paired ? 'Paired' : 'Not paired'}
                    </span>
                  </div>
                </div>
              </div>
            </div>
          )}

          <div className="card activity-panel">
            <div className="activity-panel-header">
              <div>
                <h2>Activity</h2>
                <p className="muted">
                  {selectedTerminal
                    ? `Scoped to ${selectedTerminal.name}`
                    : selectedSiteId
                      ? `Scoped to ${sites.find((s) => s.id === selectedSiteId)?.name ?? 'selected unit'}`
                      : 'All visible terminals and units'}
                </p>
              </div>
              <label className="map-filter-label activity-filter">
                Log type
                <select
                  value={activityKind}
                  onChange={(e) => setActivityKind(e.target.value as ActivityKind)}
                  aria-label="Activity log type"
                >
                  <option value="audit">Audit events</option>
                  <option value="sync">Sync-related</option>
                  <option value="keys">Key checkout / return</option>
                </select>
              </label>
            </div>

            {activityError && <div className="error-banner">{activityError}</div>}
            {activityLoading && <CircularProgress size={22} />}

            {activityRows.length ? (
              <div className="data-panel">
                <table className="data-table compact">
                  <thead>
                    <tr>
                      <th>Event</th>
                      <th>When</th>
                      <th>Detail</th>
                    </tr>
                  </thead>
                  <tbody>
                    {activityRows.map((row) => (
                      <tr key={row.id}>
                        <td className="cell-title">{row.label}</td>
                        <td>{row.when ? new Date(row.when).toLocaleString() : '—'}</td>
                        <td>{row.detail}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
                <p className="muted activity-limit-note">Showing up to 10 most recent rows.</p>
              </div>
            ) : (
              !activityLoading && (
                <div className="empty-state">No activity records for the current scope and filter.</div>
              )
            )}
          </div>
        </>
      )}
    </section>
  )
}
