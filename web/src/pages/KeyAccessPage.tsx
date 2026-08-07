import { useEffect, useMemo, useState } from 'react'
import { api, ApiError } from '../api/client'
import type { KeyAccessRequestDto, KeyDto, SiteDto, UserDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from '../components/ui'

function formatEpoch(ms?: number | null) {
  if (ms == null) return '—'
  return new Date(ms).toLocaleString()
}

/** In-flight statuses across Technician (PENDING) and Vendor Only-B (PENDING_PIC → PENDING_RA). */
const PENDING_STATUSES = new Set(['PENDING', 'PENDING_PIC', 'PENDING_RA'])

/** RA/SA can approve these (backend: Vendor requires PENDING_RA; Technician PENDING). */
const APPROVABLE_STATUSES = new Set(['PENDING', 'PENDING_RA'])

type StatusFilter =
  | 'active'
  | 'ALL'
  | 'PENDING'
  | 'PENDING_PIC'
  | 'PENDING_RA'
  | 'APPROVED'
  | 'REJECTED'
  | 'REVOKED'
  | 'EXPIRED'
  | 'CANCELLED'

function statusLabel(status: KeyAccessRequestDto['status']): string {
  switch (status) {
    case 'PENDING_PIC':
      return 'Pending PIC'
    case 'PENDING_RA':
      return 'Pending RA'
    default:
      return status
  }
}

type Props = {
  /** When set, only requests for this location are shown. */
  lockedSiteId?: string
  /** Hide page chrome when embedded in Cabinet Management. */
  embedded?: boolean
  /** Optional cabinet name for embedded copy. */
  cabinetName?: string
}

export function KeyAccessPage({ lockedSiteId, embedded = false, cabinetName }: Props) {
  const { confirmAction, dialog } = useConfirm()
  const [requests, setRequests] = useState<KeyAccessRequestDto[]>([])
  const [users, setUsers] = useState<UserDto[]>([])
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('active')
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [rows, userRows, keyRows, siteRows] = await Promise.all([
        api.listKeyAccessRequests('ALL'),
        api.listUsers(),
        api.listKeys(),
        api.listSites(),
      ])
      setRequests(rows)
      setUsers(userRows)
      setKeys(keyRows)
      setSites(siteRows)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load key access requests')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [lockedSiteId])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return requests
      .filter((r) => {
        if (lockedSiteId && r.siteId !== lockedSiteId) return false
        if (statusFilter === 'active') {
          // Include Vendor staged statuses — exact PENDING-only used to hide PENDING_RA/PENDING_PIC.
          if (!PENDING_STATUSES.has(r.status) && r.status !== 'APPROVED') return false
        } else if (statusFilter === 'PENDING') {
          // Match backend SA/RA "PENDING" queue semantics: all in-flight pending stages.
          if (!PENDING_STATUSES.has(r.status)) return false
        } else if (statusFilter !== 'ALL' && r.status !== statusFilter) {
          return false
        }
        const requester =
          r.requesterDisplayName ??
          users.find((u) => u.id === r.requesterUserId)?.displayName ??
          ''
        const site = r.siteName ?? sites.find((s) => s.id === r.siteId)?.name ?? ''
        const cabinets = (r.cabinetNames ?? []).join(' ')
        const keyNames = (r.keyIds ?? [])
          .map((id) => keys.find((k) => k.id === id)?.displayName ?? '')
          .join(' ')
        return (
          !q ||
          requester.toLowerCase().includes(q) ||
          site.toLowerCase().includes(q) ||
          cabinets.toLowerCase().includes(q) ||
          keyNames.toLowerCase().includes(q) ||
          (r.reason ?? '').toLowerCase().includes(q)
        )
      })
      .sort((a, b) => b.requestedAtEpochMillis - a.requestedAtEpochMillis)
  }, [requests, users, keys, sites, statusFilter, query, lockedSiteId])

  async function approve(id: string) {
    setBusyId(id)
    setNotice(null)
    setError(null)
    try {
      await api.approveKeyAccessRequest(id)
      setNotice('Request approved — PIN issued to the requester on mobile.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Approve failed')
    } finally {
      setBusyId(null)
    }
  }

  async function reject(id: string) {
    const ok = await confirmAction({
      title: 'Reject request?',
      message: 'The requester will see this as rejected. This cannot be undone.',
      confirmLabel: 'Reject',
    })
    if (!ok) return
    setBusyId(id)
    setNotice(null)
    setError(null)
    try {
      await api.rejectKeyAccessRequest(id)
      setNotice('Request rejected.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Reject failed')
    } finally {
      setBusyId(null)
    }
  }

  async function revoke(id: string) {
    const ok = await confirmAction({
      title: 'Revoke PIN?',
      message:
        'The approved passkey will stop working immediately at the key cabinet. The technician must apply again for a new PIN.',
      confirmLabel: 'Revoke',
      danger: true,
    })
    if (!ok) return
    setBusyId(id)
    setNotice(null)
    setError(null)
    try {
      await api.revokeKeyAccessRequest(id)
      setNotice('Access revoked — PIN cleared.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Revoke failed')
    } finally {
      setBusyId(null)
    }
  }

  const locationName =
    (lockedSiteId && sites.find((s) => s.id === lockedSiteId)?.name) ||
    (lockedSiteId ? 'this location' : null)

  return (
    <div className={embedded ? undefined : 'page'}>
      {dialog}
      {!embedded && (
        <div className="page-header">
          <div>
            <h1>Key Access</h1>
            <p className="muted">
              Approve pending exception-access requests to issue a PIN, or revoke an active PIN so it
              no longer works at the cabinet. Standing location permissions are configured under
              Cabinet Management → Key Permission.
            </p>
          </div>
          <Button type="button" variant="tonal" onClick={() => void reload()} disabled={busy}>
            Refresh
          </Button>
        </div>
      )}

      {embedded && (
        <div className="toolbar-row" style={{ marginBottom: 12, justifyContent: 'space-between' }}>
          <p className="muted" style={{ margin: 0 }}>
            Exception access for{' '}
            <strong>{cabinetName ?? 'this cabinet'}</strong>
            {locationName ? (
              <>
                {' '}
                at <strong>{locationName}</strong>
              </>
            ) : null}
            . Approve pending requests to issue a PIN, or revoke an active PIN. Standing permissions
            are configured under Key Permission.
          </p>
          <Button type="button" variant="tonal" onClick={() => void reload()} disabled={busy}>
            Refresh
          </Button>
        </div>
      )}

      {busy && <LinearProgress />}
      {error && <div className="error-banner">{error}</div>}
      {notice && <div className="notice">{notice}</div>}

      <div className="toolbar-row">
        <input
          type="search"
          placeholder={
            embedded
              ? 'Search requester, key, reason…'
              : 'Search requester, location, cabinet, key, reason…'
          }
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
        <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}>
          <option value="active">Pending + Approved</option>
          <option value="ALL">All statuses</option>
          <option value="PENDING">All pending</option>
          <option value="PENDING_PIC">Pending PIC</option>
          <option value="PENDING_RA">Pending RA</option>
          <option value="APPROVED">Approved</option>
          <option value="REJECTED">Rejected</option>
          <option value="REVOKED">Revoked</option>
          <option value="EXPIRED">Expired</option>
          <option value="CANCELLED">Cancelled</option>
        </select>
      </div>

      {filtered.length === 0 ? (
        <div className="empty-state">
          {lockedSiteId
            ? 'No key access requests for this location match this filter.'
            : 'No key access requests match this filter.'}
        </div>
      ) : (
        <div className="table-wrap">
          <table className="data-table">
            <thead>
              <tr>
                <th>Requester</th>
                {!embedded && <th>Location / Cabinet</th>}
                {embedded && <th>Cabinets</th>}
                <th>Keys</th>
                <th>Window</th>
                <th>Status</th>
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((r) => {
                const requester =
                  r.requesterDisplayName ??
                  users.find((u) => u.id === r.requesterUserId)?.displayName ??
                  r.requesterUserId
                const site = r.siteName ?? sites.find((s) => s.id === r.siteId)?.name ?? r.siteId
                const cabinets = (r.cabinetNames ?? []).join(', ') || '—'
                const keyLabel = (r.keyIds ?? [])
                  .map((id) => keys.find((k) => k.id === id)?.displayName ?? id.slice(0, 8))
                  .join(', ')
                const rowBusy = busyId === r.id
                return (
                  <tr key={r.id}>
                    <td>
                      <div className="cell-stack">
                        <strong>{requester}</strong>
                        <span className="muted">{r.requesterRole}</span>
                        {r.reason ? <span className="muted">{r.reason}</span> : null}
                      </div>
                    </td>
                    {!embedded && (
                      <td>
                        <div className="cell-stack">
                          <strong>{site}</strong>
                          <span className="muted">{cabinets}</span>
                        </div>
                      </td>
                    )}
                    {embedded && <td>{cabinets}</td>}
                    <td>{keyLabel || `${r.keyIds?.length ?? 0} key(s)`}</td>
                    <td>
                      <div className="cell-stack">
                        <span>Pickup {formatEpoch(r.pickupAtEpochMillis)}</span>
                        <span>Return {formatEpoch(r.returnAtEpochMillis)}</span>
                      </div>
                    </td>
                    <td>
                      <span className="muted">{statusLabel(r.status)}</span>
                    </td>
                    <td>
                      <div className="row-actions">
                        {APPROVABLE_STATUSES.has(r.status) && (
                          <>
                            <Button type="button" disabled={rowBusy} onClick={() => void approve(r.id)}>
                              Approve
                            </Button>
                            <Button
                              type="button"
                              variant="outlined"
                              disabled={rowBusy}
                              onClick={() => void reject(r.id)}
                            >
                              Reject
                            </Button>
                          </>
                        )}
                        {r.status === 'APPROVED' && (
                          <Button
                            type="button"
                            variant="danger"
                            disabled={rowBusy}
                            onClick={() => void revoke(r.id)}
                          >
                            Revoke PIN
                          </Button>
                        )}
                        {!APPROVABLE_STATUSES.has(r.status) && r.status !== 'APPROVED' && (
                          <span className="muted">—</span>
                        )}
                      </div>
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
        </div>
      )}
    </div>
  )
}
