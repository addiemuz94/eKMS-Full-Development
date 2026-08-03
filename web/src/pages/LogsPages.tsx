import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { ActivityLogRow, ActivitySummaryResponse, ReportCategory, SiteDto, TerminalDto } from '../api/types'
import { Button, LinearProgress, SegmentedControl } from '../components/ui'

type LogLoader = () => Promise<Record<string, unknown>[]>
type Density = 'comfortable' | 'compact'

const ALL_CATEGORIES: ReportCategory[] = [
  'KEY_TAKE',
  'KEY_RETURN',
  'CABINET_REGISTRATION',
  'PERSONNEL_REGISTRATION',
]

const CATEGORY_LABELS: Record<ReportCategory, string> = {
  KEY_TAKE: 'Key take',
  KEY_RETURN: 'Key return',
  CABINET_REGISTRATION: 'Cabinet registration',
  PERSONNEL_REGISTRATION: 'Personnel registration',
}

function fromDateInputValue(value: string): number | undefined {
  if (!value) return undefined
  const ms = new Date(`${value}T00:00:00`).getTime()
  return Number.isNaN(ms) ? undefined : ms
}

function untilDateInputValue(value: string): number | undefined {
  if (!value) return undefined
  const ms = new Date(`${value}T23:59:59.999`).getTime()
  return Number.isNaN(ms) ? undefined : ms
}

function matchesText(haystack: string | null | undefined, needle: string) {
  if (!needle.trim()) return true
  return (haystack ?? '').toLowerCase().includes(needle.trim().toLowerCase())
}

type CabinetActivityScope = 'ACTIVE' | 'DELETED'

type ArchiveCabinetOption = { id: string; name: string; siteId?: string | null }

function ActivityLogsView({ scope }: { scope: CabinetActivityScope }) {
  const isArchive = scope === 'DELETED'
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [archiveCabinets, setArchiveCabinets] = useState<ArchiveCabinetOption[]>([])
  const [fromDate, setFromDate] = useState('')
  const [untilDate, setUntilDate] = useState('')
  const [fromInput, setFromInput] = useState('')
  const [untilInput, setUntilInput] = useState('')
  const [filterSiteId, setFilterSiteId] = useState('')
  const [filterTerminalId, setFilterTerminalId] = useState('')
  const [filterCategory, setFilterCategory] = useState('')
  const [filterEvent, setFilterEvent] = useState('')
  const [filterUser, setFilterUser] = useState('')
  const [filterDetail, setFilterDetail] = useState('')
  const [items, setItems] = useState<ActivityLogRow[]>([])
  const [summary, setSummary] = useState<ActivitySummaryResponse | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [pdfBusy, setPdfBusy] = useState(false)
  const [density, setDensity] = useState<Density>('comfortable')

  const cabinetOptions = useMemo(() => {
    if (isArchive) {
      return filterSiteId
        ? archiveCabinets.filter((t) => t.siteId === filterSiteId || t.siteId == null)
        : archiveCabinets
    }
    // Activity Report: only active cabinets — never recycle-bin / purged names.
    const active = terminals.filter((t) => !t.lifecycle?.state || t.lifecycle.state === 'ACTIVE')
    return filterSiteId ? active.filter((t) => t.siteId === filterSiteId) : active
  }, [isArchive, filterSiteId, archiveCabinets, terminals])

  const locationOptions = useMemo(() => {
    if (isArchive) {
      const siteIds = new Set(
        archiveCabinets.map((c) => c.siteId).filter((id): id is string => Boolean(id)),
      )
      for (const row of items) {
        if (row.siteId) siteIds.add(row.siteId)
      }
      if (!siteIds.size) return sites
      return sites.filter((s) => siteIds.has(s.id))
    }
    const activeSiteIds = new Set(
      terminals
        .filter((t) => !t.lifecycle?.state || t.lifecycle.state === 'ACTIVE')
        .map((t) => t.siteId),
    )
    return sites.filter((s) => activeSiteIds.has(s.id))
  }, [isArchive, archiveCabinets, items, sites, terminals])

  useEffect(() => {
    if (!filterSiteId) return
    if (!locationOptions.some((s) => s.id === filterSiteId)) {
      setFilterSiteId('')
      setFilterTerminalId('')
    }
  }, [locationOptions, filterSiteId])

  useEffect(() => {
    void (async () => {
      try {
        if (isArchive) {
          const [s, bin] = await Promise.all([api.listSites(), api.listRecycleBin()])
          setSites(s)
          setArchiveCabinets(
            bin
              .filter((entry) => entry.recordType === 'TERMINAL')
              .map((entry) => ({
                id: entry.recordId,
                name: entry.recordLabel,
                siteId: null,
              })),
          )
        } else {
          const [s, t] = await Promise.all([api.listSites(), api.listTerminals()])
          setSites(s)
          setTerminals(t.filter((row) => !row.lifecycle?.state || row.lifecycle.state === 'ACTIVE'))
        }
      } catch (err) {
        setError(err instanceof ApiError ? err.message : 'Failed to load filters')
      }
    })()
  }, [isArchive])

  function apiFilterPayload() {
    return {
      siteId: filterSiteId || undefined,
      terminalId: filterTerminalId || undefined,
      fromEpochMillis: fromDateInputValue(fromDate),
      untilEpochMillis: untilDateInputValue(untilDate),
      categories: filterCategory ? ([filterCategory] as ReportCategory[]) : undefined,
      limit: 500,
      cabinetScope: scope,
    }
  }

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const filter = apiFilterPayload()
      const [rows, sum] = await Promise.all([
        api.listActivityLogs(filter),
        api.getActivitySummary(filter),
      ])
      setItems(rows)
      setSummary(sum)
      if (isArchive) {
        setArchiveCabinets((prev) => {
          const byId = new Map(prev.map((c) => [c.id, c]))
          for (const row of rows) {
            if (!row.terminalId) continue
            const existing = byId.get(row.terminalId)
            byId.set(row.terminalId, {
              id: row.terminalId,
              name: row.terminalName || existing?.name || row.terminalId,
              siteId: row.siteId ?? existing?.siteId ?? null,
            })
          }
          return [...byId.values()].sort((a, b) => a.name.localeCompare(b.name))
        })
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load activity logs')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
    // eslint-disable-next-line react-hooks/exhaustive-deps -- date / location / cabinet / category drive server reload
  }, [fromDate, untilDate, filterSiteId, filterTerminalId, filterCategory, scope])

  useEffect(() => {
    if (!filterTerminalId) return
    const allowed = new Set(cabinetOptions.map((c) => c.id))
    if (!allowed.has(filterTerminalId)) setFilterTerminalId('')
  }, [cabinetOptions, filterTerminalId])

  const activeTerminalIds = useMemo(
    () => new Set(terminals.map((t) => t.id)),
    [terminals],
  )

  const visibleItems = useMemo(() => {
    return items.filter((item) => {
      if (!isArchive && item.terminalId && !activeTerminalIds.has(item.terminalId)) {
        return false
      }
      const userLabel = item.actorName ?? item.actorUserId ?? ''
      return (
        matchesText(item.eventType, filterEvent) &&
        matchesText(userLabel, filterUser) &&
        matchesText(item.detail, filterDetail)
      )
    })
  }, [items, isArchive, activeTerminalIds, filterEvent, filterUser, filterDetail])

  async function onDownloadPdf() {
    setPdfBusy(true)
    setError(null)
    try {
      const filter = apiFilterPayload()
      const job = await api.createReportExport({
        kind: 'ACTIVITY_LOGS',
        format: 'PDF',
        filter: {
          siteId: filter.siteId,
          terminalId: filter.terminalId,
          fromEpochMillis: filter.fromEpochMillis,
          untilEpochMillis: filter.untilEpochMillis,
          categories: filter.categories,
          limit: 500,
          cabinetScope: filter.cabinetScope,
        },
      })
      const siteName = sites.find((s) => s.id === filterSiteId)?.name ?? 'all'
      const prefix = isArchive ? 'activity-archive' : 'activity-logs'
      await api.downloadReportExport(job.downloadPath, `${prefix}-${siteName}.pdf`)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'PDF export failed')
    } finally {
      setPdfBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>{isArchive ? 'Activity archive' : 'Activity Report'}</h1>
          <p className="muted">
            {isArchive
              ? 'Same filters and PDF export as Activity Report, for cabinets that have been removed.'
              : 'Key take/return, cabinet registration, and personnel registration for active cabinets. Removed-cabinet history is under Activity archive.'}
          </p>
        </div>
        <div className="toolbar-row" style={{ gap: 8 }}>
          <Button variant="filled" loading={pdfBusy} onClick={() => void onDownloadPdf()}>
            Generate PDF
          </Button>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading activity" />}

      <div className="toolbar-row" style={{ flexWrap: 'wrap', gap: 12 }}>
        <label>
          From
          <input
            type="date"
            value={fromInput}
            onChange={(e) => setFromInput(e.target.value)}
            onBlur={() => {
              if (fromInput !== fromDate) setFromDate(fromInput)
            }}
          />
        </label>
        <label>
          To
          <input
            type="date"
            value={untilInput}
            onChange={(e) => setUntilInput(e.target.value)}
            onBlur={() => {
              if (untilInput !== untilDate) setUntilDate(untilInput)
            }}
          />
        </label>
        <SegmentedControl
          ariaLabel="Table density"
          value={density}
          onChange={setDensity}
          options={[
            { value: 'comfortable', label: 'Comfortable' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
        <span className="muted" style={{ marginLeft: 'auto', fontSize: 13 }}>
          {isArchive ? (
            <>
              See also: <Link to="/activity-report">Activity Report</Link>
            </>
          ) : (
            <>
              Legacy:{' '}
              <Link to="/key-records">Pickup &amp; Return</Link>
              {' · '}
              <Link to="/system-logs">System</Link>
              {' · '}
              <Link to="/equipment-logs">Equipment</Link>
              {' · '}
              <Link to="/activity-archive">Archive</Link>
            </>
          )}
        </span>
      </div>

      {summary && (
        <div className="metrics" style={{ marginTop: 16 }}>
          <div className="metric surface">
            <div className="metric-label">Total</div>
            <strong>{summary.total}</strong>
          </div>
          {ALL_CATEGORIES.map((cat) => (
            <div key={cat} className="metric surface">
              <div className="metric-label">{CATEGORY_LABELS[cat]}</div>
              <strong>{summary.byCategory[cat] ?? 0}</strong>
            </div>
          ))}
        </div>
      )}

      <div className="data-panel" style={{ marginTop: 16 }}>
        <table className={`data-table activity-report-table${density === 'compact' ? ' compact' : ''}`}>
          <thead>
            <tr>
              <th>Date/Time</th>
              <th>Location</th>
              <th>Cabinet</th>
              <th>Category</th>
              <th>Event</th>
              <th>User</th>
              <th>Detail</th>
            </tr>
            <tr className="data-table-filters">
              <th aria-label="Date/Time filter">
                <span className="data-table-filter-hint">From / To above</span>
              </th>
              <th>
                <select
                  aria-label="Filter by location"
                  value={filterSiteId}
                  onChange={(e) => {
                    setFilterSiteId(e.target.value)
                    setFilterTerminalId('')
                  }}
                >
                  <option value="">All</option>
                  {locationOptions.map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
                </select>
              </th>
              <th>
                <select
                  aria-label="Filter by cabinet"
                  value={filterTerminalId}
                  onChange={(e) => setFilterTerminalId(e.target.value)}
                >
                  <option value="">All</option>
                  {cabinetOptions.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name}
                    </option>
                  ))}
                </select>
              </th>
              <th>
                <select
                  aria-label="Filter by category"
                  value={filterCategory}
                  onChange={(e) => setFilterCategory(e.target.value)}
                >
                  <option value="">All</option>
                  {ALL_CATEGORIES.map((cat) => (
                    <option key={cat} value={cat}>
                      {CATEGORY_LABELS[cat]}
                    </option>
                  ))}
                </select>
              </th>
              <th>
                <input
                  type="search"
                  aria-label="Filter by event"
                  placeholder="Filter…"
                  value={filterEvent}
                  onChange={(e) => setFilterEvent(e.target.value)}
                />
              </th>
              <th>
                <input
                  type="search"
                  aria-label="Filter by user"
                  placeholder="Filter…"
                  value={filterUser}
                  onChange={(e) => setFilterUser(e.target.value)}
                />
              </th>
              <th>
                <input
                  type="search"
                  aria-label="Filter by detail"
                  placeholder="Filter…"
                  value={filterDetail}
                  onChange={(e) => setFilterDetail(e.target.value)}
                />
              </th>
            </tr>
          </thead>
          <tbody>
            {visibleItems.length ? (
              visibleItems.map((item) => (
                <tr key={item.id}>
                  <td className="mono">{new Date(item.occurredAtEpochMillis).toLocaleString()}</td>
                  <td>{item.siteName ?? '—'}</td>
                  <td>{item.terminalName ?? '—'}</td>
                  <td>{CATEGORY_LABELS[item.category] ?? item.category}</td>
                  <td className="cell-title">{item.eventType}</td>
                  <td>{item.actorName ?? item.actorUserId ?? '—'}</td>
                  <td>{item.detail ?? '—'}</td>
                </tr>
              ))
            ) : (
              !busy && (
                <tr>
                  <td colSpan={7} className="empty-state" style={{ border: 'none' }}>
                    No activity matches these filters.
                  </td>
                </tr>
              )
            )}
          </tbody>
        </table>
      </div>
    </section>
  )
}

export function ActivityReportPage() {
  return <ActivityLogsView scope="ACTIVE" />
}

export function ActivityArchivePage() {
  return <ActivityLogsView scope="DELETED" />
}

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
  const [density, setDensity] = useState<Density>('comfortable')

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
        <Button variant="tonal" loading={busy} onClick={() => void reload()}>
          Refresh
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading logs" />}

      <div className="toolbar-row">
        <SegmentedControl
          ariaLabel="Table density"
          value={density}
          onChange={setDensity}
          options={[
            { value: 'comfortable', label: 'Comfortable' },
            { value: 'compact', label: 'Compact' },
          ]}
        />
        <Link to="/activity-report" className="muted" style={{ marginLeft: 'auto', fontSize: 13 }}>
          ← Activity Report
        </Link>
      </div>

      {items.length ? (
        <div className="data-panel">
          <table className={`data-table${density === 'compact' ? ' compact' : ''}`}>
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
                  <td className="mono">
                    {item.occurredAtEpochMillis || item.createdAtEpochMillis
                      ? new Date(
                          Number(item.occurredAtEpochMillis ?? item.createdAtEpochMillis),
                        ).toLocaleString()
                      : '—'}
                  </td>
                  <td className="mono">{String(item.actorUserId ?? '—')}</td>
                  <td>{String(item.detail ?? '—')}</td>
                  <td className="mono">
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
      description="Login, account, deleted-items and configuration events."
      load={api.listSystemLogs}
    />
  )
}

export function EquipmentLogsPage() {
  return (
    <LogsPage
      title="Equipment Operation Log"
      description="Hardware-related key and cabinet events reported to the server."
      load={api.listEquipmentLogs}
    />
  )
}
