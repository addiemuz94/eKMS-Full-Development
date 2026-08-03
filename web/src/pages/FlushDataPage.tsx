import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  AlertTriangle,
  Building2,
  Eraser,
  KeyRound,
  MapPinned,
  Shield,
  Trash2,
  Users,
} from 'lucide-react'
import { api, ApiError, type FlushPreviewResponse, type FlushScope } from '../api/client'
import { Button, LinearProgress, useConfirm } from '../components/ui'

type CategoryScope = Exclude<FlushScope, 'ALL'>

type ScopeMeta = {
  scope: CategoryScope
  title: string
  blurb: string
  icon: typeof KeyRound
}

const CATEGORY_SCOPES: ScopeMeta[] = [
  {
    scope: 'ACCESS_GRANTS',
    title: 'Key permissions',
    blurb: 'Remove all key permissions. Keys and cabinets are retained.',
    icon: Shield,
  },
  {
    scope: 'KEYS',
    title: 'Keys',
    blurb: 'Permanently delete all managed keys. Referencing permissions are cleared first.',
    icon: KeyRound,
  },
  {
    scope: 'TERMINALS',
    title: 'Cabinets',
    blurb: 'Permanently delete all cabinets, slots, settings, and keys bound to those slots.',
    icon: Building2,
  },
  {
    scope: 'USERS',
    title: 'Personnel',
    blurb: 'Permanently delete non–Super Admin personnel accounts. Super Admin logins are retained.',
    icon: Users,
  },
  {
    scope: 'SITES',
    title: 'Locations',
    blurb: 'Permanently delete locations and related configuration after clearing cabinets, keys, and permissions.',
    icon: MapPinned,
  },
]

const COUNT_LABELS: Record<string, string> = {
  terminals: 'Cabinets',
  keySlots: 'Key slots',
  keys: 'Keys',
  accessGrants: 'Key permissions',
  users: 'Personnel (non–Super Admin)',
  sites: 'Locations',
  eventDefinitions: 'Event definitions',
  schedules: 'Schedules',
  personnelGroups: 'User groups',
  keyGroups: 'Key groups',
  multiAuthRules: 'Two-person approval rules',
}

function countEntries(counts: Record<string, number>) {
  return Object.entries(counts)
    .filter(([, n]) => typeof n === 'number' && n > 0)
    .map(([k, n]) => ({
      key: k,
      label: COUNT_LABELS[k] ?? k,
      count: n,
    }))
}

function formatCountsForDialog(counts: Record<string, number>) {
  const rows = countEntries(counts)
  if (!rows.length) return 'Nothing matching this scope was counted (database may already be empty).'
  return rows.map((r) => `• ${r.label}: ${r.count}`).join('\n')
}

function scopeTitle(scope: FlushScope) {
  if (scope === 'ALL') return 'Erase everything'
  return CATEGORY_SCOPES.find((s) => s.scope === scope)?.title ?? scope
}

export function FlushDataPage() {
  const { confirmDangerTwice, dialog } = useConfirm()
  const [selected, setSelected] = useState<CategoryScope | null>(null)
  const [preview, setPreview] = useState<FlushPreviewResponse | null>(null)
  const [previewBusy, setPreviewBusy] = useState(false)
  const [flushBusy, setFlushBusy] = useState(false)
  const [flushingAll, setFlushingAll] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [lastResult, setLastResult] = useState<{
    scope: FlushScope
    deleted: Record<string, number>
  } | null>(null)
  const [previewNonce, setPreviewNonce] = useState(0)

  useEffect(() => {
    if (!selected) {
      setPreview(null)
      return
    }
    let cancelled = false
    setPreviewBusy(true)
    setError(null)
    setPreview(null)
    void (async () => {
      try {
        const next = await api.previewFlush(selected)
        if (!cancelled) setPreview(next)
      } catch (err) {
        if (!cancelled) {
          setError(err instanceof ApiError ? err.message : 'Failed to load erase preview')
          setPreview(null)
        }
      } finally {
        if (!cancelled) setPreviewBusy(false)
      }
    })()
    return () => {
      cancelled = true
    }
  }, [selected, previewNonce])

  async function executeFlush(scope: FlushScope, dialogPreview: FlushPreviewResponse) {
    const title = scopeTitle(scope)
    if (
      !(await confirmDangerTwice({
        title: `Erase ${title}?`,
        firstMessage: `This permanently deletes data for “${title}”.\n\n${formatCountsForDialog(dialogPreview.counts)}\n\n${dialogPreview.note ?? 'Super Admin accounts and audit history are retained.'}`,
        secondMessage: `Final warning: this cannot be undone and does not go to deleted items.\n\nErase “${title}” now?`,
        firstConfirmLabel: 'Continue',
        secondConfirmLabel: 'Erase permanently',
      }))
    ) {
      return
    }

    setFlushBusy(true)
    setError(null)
    setNotice(null)
    try {
      // Re-issue preview so the one-time token is fresh after the dialog wait.
      const fresh = await api.previewFlush(scope)
      const result = await api.flushData({
        scope,
        confirmToken: fresh.confirmTokenRequired,
        previewToken: fresh.previewToken,
      })
      setNotice(`Erased “${title}” permanently.`)
      setLastResult({ scope, deleted: result.deleted })
      setSelected(null)
      setPreview(null)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Erase failed')
    } finally {
      setFlushBusy(false)
      setFlushingAll(false)
    }
  }

  async function onFlushSelected() {
    if (!selected || !preview) return
    await executeFlush(selected, preview)
  }

  async function onFlushEverything() {
    setNotice(null)
    setLastResult(null)
    setError(null)
    setFlushingAll(true)
    try {
      const allPreview = await api.previewFlush('ALL')
      await executeFlush('ALL', allPreview)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to preview erase-all')
      setFlushingAll(false)
    }
  }

  const previewRows = preview ? countEntries(preview.counts) : []
  const resultRows = lastResult ? countEntries(lastResult.deleted) : []
  const busy = previewBusy || flushBusy

  return (
    <section className="flush-page">
      <div className="page-header">
        <div>
          <h1>Erase data</h1>
          <p className="muted">
            Super Admin only. Permanently delete selected portal data. Requires two confirmations.
            Erased data cannot be restored from Deleted items.
          </p>
        </div>
      </div>

      <div className="flush-callout" role="note">
        <AlertTriangle className="flush-callout-icon" size={22} aria-hidden />
        <div>
          <strong>What stays</strong>
          <p className="muted" style={{ margin: '4px 0 0' }}>
            Super Admin / system accounts and historic audit events are never deleted. Use{' '}
            <Link to="/recycle-bin">Deleted items</Link> when you only need to remove a few records
            (you can restore them for 60 days).
          </p>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice">{notice}</div>}
      {flushBusy && <LinearProgress className="table-busy" label="Erasing…" />}

      {resultRows.length > 0 && lastResult && (
        <div className="flush-result data-panel">
          <h2 className="flush-section-title">Last erase — {scopeTitle(lastResult.scope)}</h2>
          <div className="metrics">
            {resultRows.map((row) => (
              <div className="metric surface" key={row.key}>
                <div className="metric-label">{row.label}</div>
                <strong>{row.count}</strong>
              </div>
            ))}
          </div>
        </div>
      )}

      <h2 className="flush-section-title">Choose a category</h2>
      <p className="muted flush-section-copy">
        Select a category to preview live counts, then confirm twice to permanently delete that scope.
      </p>

      <div className="flush-grid" role="listbox" aria-label="Erase categories">
        {CATEGORY_SCOPES.map((item) => {
          const Icon = item.icon
          const active = selected === item.scope
          return (
            <button
              type="button"
              key={item.scope}
              role="option"
              aria-selected={active}
              className={`flush-card${active ? ' selected' : ''}`}
              disabled={flushBusy}
              onClick={() => {
                setNotice(null)
                setLastResult(null)
                setSelected(item.scope)
              }}
            >
              <span className="flush-card-icon" aria-hidden>
                <Icon size={20} />
              </span>
              <span className="flush-card-body">
                <span className="flush-card-title">{item.title}</span>
                <span className="flush-card-blurb muted">{item.blurb}</span>
              </span>
            </button>
          )
        })}
      </div>

      <div className={`flush-preview data-panel${selected ? '' : ' is-idle'}`}>
        {!selected && (
          <div className="empty-state" style={{ margin: 0 }}>
            Select a category above to load a live preview.
          </div>
        )}
        {selected && (
          <>
            <div className="flush-preview-header">
              <div>
                <h2 className="flush-section-title" style={{ marginBottom: 4 }}>
                  Preview — {scopeTitle(selected)}
                </h2>
                <p className="muted" style={{ margin: 0, fontSize: '0.88rem' }}>
                  Counts refresh when you select a category. Erase uses a fresh token at confirm time.
                </p>
              </div>
              <Button
                variant="tonal"
                disabled={busy}
                onClick={() => setPreviewNonce((n) => n + 1)}
              >
                Refresh
              </Button>
            </div>

            {previewBusy && <LinearProgress className="table-busy" label="Loading preview" />}

            {!previewBusy && preview && previewRows.length === 0 && (
              <div className="empty-state" style={{ margin: '12px 0 0' }}>
                Nothing to erase in this scope right now.
              </div>
            )}

            {!previewBusy && previewRows.length > 0 && (
              <div className="metrics" style={{ marginTop: 12 }}>
                {previewRows.map((row) => (
                  <div className="metric surface" key={row.key}>
                    <div className="metric-label">{row.label}</div>
                    <strong>{row.count}</strong>
                  </div>
                ))}
              </div>
            )}

            <div className="flush-preview-actions">
              <Button
                variant="danger"
                icon={Trash2}
                disabled={!preview || previewBusy || flushBusy || previewRows.length === 0}
                loading={flushBusy && !flushingAll}
                onClick={() => void onFlushSelected()}
              >
                Erase {scopeTitle(selected).toLowerCase()}…
              </Button>
            </div>
          </>
        )}
      </div>

      <div className="flush-danger-zone data-panel">
        <div className="flush-danger-zone-header">
          <Eraser size={22} aria-hidden />
          <div>
            <h2 className="flush-section-title" style={{ marginBottom: 4 }}>
              Erase everything
            </h2>
            <p className="muted" style={{ margin: 0, fontSize: '0.88rem' }}>
              Ordered permanent deletion of permissions → keys → cabinets → non–Super Admin personnel →
              locations. Audit history is retained. Use only for a full system reset.
            </p>
          </div>
        </div>
        <Button
          variant="danger"
          icon={AlertTriangle}
          disabled={flushBusy || flushingAll}
          loading={flushingAll}
          onClick={() => void onFlushEverything()}
        >
          Erase everything…
        </Button>
      </div>

      {dialog}
    </section>
  )
}
