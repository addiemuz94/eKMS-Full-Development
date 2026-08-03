import { useEffect, useMemo, useState } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { layoutSummary } from '../api/cabinetLayout'
import type { SiteDto, TerminalDto } from '../api/types'
import { CabinetSettingsPanel } from '../components/CabinetSettingsPanel'
import { Button, LinearProgress, SegmentedControl, useConfirm } from '../components/ui'

type PairingBanner = {
  code: string
  expiresAtEpochMillis: number
  terminalName: string
  terminalId: string
  regenerated?: boolean
}

type PairedFilter = 'all' | 'paired' | 'unpaired'

function normalizeProvince(value: string | null | undefined) {
  return (value ?? '').trim()
}

export function TerminalsPage() {
  const navigate = useNavigate()
  const { confirmAction, confirmDangerTwice, dialog } = useConfirm()
  const [searchParams, setSearchParams] = useSearchParams()
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [query, setQuery] = useState('')
  const [provinceFilter, setProvinceFilter] = useState('all')
  const [selectedSiteId, setSelectedSiteId] = useState<string | null>(null)
  const [pairedFilter, setPairedFilter] = useState<PairedFilter>('all')
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [pairing, setPairing] = useState<PairingBanner | null>(null)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [siteRows, terminalRows] = await Promise.all([api.listSites(), api.listTerminals()])
      setSites(siteRows)
      setTerminals(terminalRows)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinets')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  // Apply deep-link once per terminalId value (not on every terminals/sites reload).
  const deepLinkTerminalId = searchParams.get('terminalId')
  useEffect(() => {
    if (!deepLinkTerminalId || !terminals.length || !sites.length) return
    const terminal = terminals.find((t) => t.id === deepLinkTerminalId)
    if (!terminal) return
    setSelectedSiteId(terminal.siteId)
    setSelectedId(terminal.id)
    const site = sites.find((s) => s.id === terminal.siteId)
    const province = normalizeProvince(site?.province)
    if (province) setProvinceFilter(province)
  }, [deepLinkTerminalId, terminals, sites])

  const provinceOptions = useMemo(() => {
    const names = new Set<string>()
    for (const site of sites) {
      const province = normalizeProvince(site.province)
      if (province) names.add(province)
    }
    return [...names].sort((a, b) => a.localeCompare(b))
  }, [sites])

  const cabinetCountByProvince = useMemo(() => {
    const counts = new Map<string, number>()
    for (const terminal of terminals) {
      const site = sites.find((s) => s.id === terminal.siteId)
      const province = normalizeProvince(site?.province)
      if (!province) continue
      counts.set(province, (counts.get(province) ?? 0) + 1)
    }
    return counts
  }, [terminals, sites])

  const cabinetCountBySiteId = useMemo(() => {
    const counts = new Map<string, number>()
    for (const terminal of terminals) {
      counts.set(terminal.siteId, (counts.get(terminal.siteId) ?? 0) + 1)
    }
    return counts
  }, [terminals])

  const sitesInProvince = useMemo(() => {
    if (provinceFilter === 'all') return sites
    return sites.filter((site) => normalizeProvince(site.province) === provinceFilter)
  }, [sites, provinceFilter])

  const locationSelectValue =
    selectedSiteId && sitesInProvince.some((site) => site.id === selectedSiteId)
      ? selectedSiteId
      : ''

  useEffect(() => {
    if (!selectedSiteId) return
    if (!sitesInProvince.some((site) => site.id === selectedSiteId)) {
      setSelectedSiteId(null)
      setSelectedId(null)
      if (searchParams.get('terminalId')) {
        setSearchParams({}, { replace: true })
      }
    }
  }, [sitesInProvince, selectedSiteId, searchParams, setSearchParams])

  const selectedSite = useMemo(
    () => (selectedSiteId ? sites.find((s) => s.id === selectedSiteId) ?? null : null),
    [sites, selectedSiteId],
  )

  const visible = useMemo(() => {
    if (!selectedSiteId) return []
    const q = query.trim().toLowerCase()
    return terminals.filter((t) => {
      if (t.siteId !== selectedSiteId) return false
      const matchQ = !q || t.name.toLowerCase().includes(q)
      const isPaired = Boolean(t.paired)
      const matchPaired =
        pairedFilter === 'all' || (pairedFilter === 'paired' ? isPaired : !isPaired)
      return matchQ && matchPaired
    })
  }, [terminals, selectedSiteId, query, pairedFilter])

  const filtersActive = query.trim() !== '' || pairedFilter !== 'all'

  function clearCabinetFilters() {
    setQuery('')
    setPairedFilter('all')
  }

  function clearCabinetSelection() {
    setSelectedId(null)
    if (searchParams.get('terminalId')) {
      setSearchParams({}, { replace: true })
    }
  }

  function chooseProvince(nextProvince: string) {
    setProvinceFilter(nextProvince)
    setSelectedSiteId(null)
    clearCabinetSelection()
    setQuery('')
    setPairedFilter('all')
  }

  function chooseLocation(nextSiteId: string) {
    if (!nextSiteId) {
      setSelectedSiteId(null)
      clearCabinetSelection()
      return
    }
    setSelectedSiteId(nextSiteId)
    clearCabinetSelection()
    setQuery('')
    setPairedFilter('all')
  }

  const selected = useMemo(
    () => (selectedId ? terminals.find((t) => t.id === selectedId) ?? null : null),
    [terminals, selectedId],
  )

  function siteName(id: string) {
    return sites.find((site) => site.id === id)?.name ?? 'Unassigned location'
  }

  function siteLocation(id: string) {
    const site = sites.find((s) => s.id === id)
    if (!site) return null
    const parts = [site.province, site.city].filter(Boolean)
    return parts.length ? parts.join(', ') : null
  }

  function selectCabinet(terminal: TerminalDto) {
    setSelectedSiteId(terminal.siteId)
    setSelectedId(terminal.id)
    setSearchParams({ terminalId: terminal.id }, { replace: true })
  }

  async function onRegenerate(terminal: TerminalDto) {
    if (
      !(await confirmAction({
        message:
          'Regenerate setup code? This immediately revokes any existing device session for this cabinet. Give the new code only to the on-site technician.',
        danger: true,
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      const result = await api.regenerateTerminalPairingCode(terminal.id)
      setPairing({
        code: result.code,
        expiresAtEpochMillis: result.expiresAtEpochMillis,
        terminalName: terminal.name,
        terminalId: terminal.id,
        regenerated: true,
      })
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to regenerate setup code')
    } finally {
      setBusy(false)
    }
  }

  async function onArchive(id: string) {
    const terminal = terminals.find((t) => t.id === id)
    const cabName = terminal?.name ?? 'this cabinet'
    if (
      !(await confirmDangerTwice({
        title: 'Delete cabinet',
        firstMessage: `Delete “${cabName}”? It will move to Deleted items and can be restored for 60 days.\n\nThis also includes its key slots, keys assigned to those slots, and key permissions that reference those keys.`,
        secondMessage: `Final warning: delete “${cabName}” and its related contents? They will move to Deleted items (can restore for 60 days).`,
        firstConfirmLabel: 'Continue',
        secondConfirmLabel: 'Delete',
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.deleteTerminal(id, { cascade: true })
      if (selectedId === id) {
        setSelectedId(null)
        setSearchParams({}, { replace: true })
      }
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to archive cabinet')
    } finally {
      setBusy(false)
    }
  }

  function layoutLabel(terminal: TerminalDto) {
    return `Cabinet number ${terminal.boxAddress} · ${layoutSummary(terminal)}`
  }

  const detailEmpty = !selectedSiteId
    ? {
        title: 'Select a location',
        body: (
          <>
            Select a location on the left, then choose a cabinet to configure. Register new locations
            under <Link to="/registration">Registration</Link>.
          </>
        ),
      }
    : {
        title: 'Select a cabinet',
        body: (
          <>
            Select a cabinet in <strong>{selectedSite?.name ?? 'this location'}</strong> to open its
            settings on the right.
          </>
        ),
      }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Cabinet Management</h1>
          <p className="muted">
            Select a location, then a cabinet. Configure that cabinet on the right (cabinet identity, location, timers, personnel
            assignment, keys, key permission, key access, and related settings). Register new
            locations under Registration.
          </p>
        </div>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading cabinets" />}

      {pairing && (
        <div className="notice pairing-code-banner" role="status">
          <h3>{pairing.regenerated ? 'New setup code' : 'Setup code (copy before leaving this page)'}</h3>
          <p className="muted">
            For <strong>{pairing.terminalName}</strong>. Shown once — the server stores only a hash and
            cannot display this code again. Expires{' '}
            {new Date(pairing.expiresAtEpochMillis).toLocaleString()}.
          </p>
          <p className="pairing-code-digits mono">{pairing.code}</p>
          <p className="muted mono" style={{ fontSize: '0.85rem' }}>
            Key Cabinet ID: {pairing.terminalId}
          </p>
          <Button variant="outlined" icon={X} onClick={() => setPairing(null)}>
            Dismiss
          </Button>
        </div>
      )}

      <div className="cabinets-hub">
        <aside className="cabinets-list data-panel">
          <div className="cabinets-list-filters">
            <div className="cabinets-filter-grid">
              <label className="cabinets-filter-field">
                <span className="cabinets-filter-label">State / province</span>
                <select
                  value={provinceFilter}
                  onChange={(e) => chooseProvince(e.target.value)}
                  disabled={provinceOptions.length === 0}
                >
                  <option value="all">All states ({terminals.length})</option>
                  {provinceOptions.map((province) => {
                    const count = cabinetCountByProvince.get(province) ?? 0
                    return (
                      <option key={province} value={province}>
                        {province} ({count})
                      </option>
                    )
                  })}
                </select>
              </label>

              <label className="cabinets-filter-field">
                <span className="cabinets-filter-label">Location</span>
                <select
                  value={locationSelectValue}
                  onChange={(e) => chooseLocation(e.target.value)}
                  aria-label="Select location"
                >
                  <option value="">Select location…</option>
                  {sitesInProvince.map((site) => {
                    const count = cabinetCountBySiteId.get(site.id) ?? 0
                    return (
                      <option key={site.id} value={site.id}>
                        {site.name}
                        {site.city ? ` · ${site.city}` : ''} ({count})
                      </option>
                    )
                  })}
                </select>
              </label>
            </div>

            {selectedSiteId && (
              <>
                <label className="cabinets-filter-field cabinets-filter-search">
                  <span className="cabinets-filter-label">Search cabinets</span>
                  <input
                    className="search"
                    placeholder="Cabinet name…"
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    aria-label="Search cabinets in location"
                  />
                </label>

                <div className="cabinets-filter-field">
                  <span className="cabinets-filter-label">Setup</span>
                  <SegmentedControl<PairedFilter>
                    ariaLabel="Setup status filter"
                    value={pairedFilter}
                    onChange={setPairedFilter}
                    options={[
                      { value: 'all', label: 'All' },
                      { value: 'paired', label: 'Set up' },
                      { value: 'unpaired', label: 'Not set up' },
                    ]}
                  />
                </div>

                <div className="cabinets-filter-footer">
                  <span className="muted cabinets-filter-count">
                    {visible.length} cabinet{visible.length === 1 ? '' : 's'} in this location
                  </span>
                  {filtersActive && (
                    <Button variant="link" onClick={clearCabinetFilters}>
                      Clear filters
                    </Button>
                  )}
                </div>
              </>
            )}
          </div>

          <div className="cabinets-list-scroll">
            {!selectedSiteId ? (
              !busy && (
                <div className="empty-state" style={{ margin: 12 }}>
                  Select a location to see its cabinets.
                </div>
              )
            ) : visible.length ? (
              <ul className="cabinets-rail">
                {visible.map((terminal) => {
                  const active = terminal.id === selectedId
                  return (
                    <li key={terminal.id}>
                      <button
                        type="button"
                        className={`cabinets-rail-item${active ? ' selected' : ''}${
                          terminal.paired ? '' : ' unpaired'
                        }`}
                        onClick={() => selectCabinet(terminal)}
                      >
                        <span className="cabinets-rail-top">
                          <span className="cabinets-rail-name">{terminal.name}</span>
                          <span className={`badge${terminal.paired ? ' badge-success' : ' badge-outline'}`}>
                            {terminal.paired ? 'Set up' : 'Not set up'}
                          </span>
                        </span>
                        <span className="cabinets-rail-meta muted">{layoutLabel(terminal)}</span>
                      </button>
                    </li>
                  )
                })}
              </ul>
            ) : (
              !busy && (
                <div className="empty-state" style={{ margin: 12 }}>
                  No cabinets in this location
                  {filtersActive ? (
                    <>
                      {' '}
                      match filters.{' '}
                      <button type="button" className="linkish" onClick={clearCabinetFilters}>
                        Clear filters
                      </button>
                    </>
                  ) : (
                    <>
                      .{' '}
                      <Link to="/registration">Register under Registration</Link>
                    </>
                  )}
                </div>
              )
            )}
          </div>
        </aside>

        <div className="cabinets-detail data-panel">
          {selected && selectedSiteId === selected.siteId ? (
            <>
              <header className="cabinets-detail-header">
                <div>
                  <p className="cabinets-detail-eyebrow">Selected cabinet</p>
                  <h2 className="cabinets-detail-title">{selected.name}</h2>
                  <p className="muted" style={{ margin: '4px 0 0' }}>
                    {siteName(selected.siteId)}
                    {siteLocation(selected.siteId) ? ` · ${siteLocation(selected.siteId)}` : ''}
                    {' · '}
                    {layoutLabel(selected)}
                  </p>
                  <p className="muted mono" style={{ margin: '4px 0 0', fontSize: '0.82rem' }}>
                    {selected.id}
                  </p>
                </div>
                <div className="cabinets-detail-actions">
                  <div className="cabinets-detail-actions-primary">
                    <span className={`badge${selected.paired ? ' badge-success' : ' badge-outline'}`}>
                      {selected.paired ? 'Set up' : 'Not set up'}
                    </span>
                  </div>
                  <div className="cabinets-detail-actions-secondary">
                    <Button
                      variant="outlined"
                      disabled={busy}
                      onClick={() => void onRegenerate(selected)}
                    >
                      Setup code
                    </Button>
                    <Button
                      variant="outlined"
                      onClick={() => navigate(`/data-sync?terminalId=${selected.id}`)}
                    >
                      Data sync
                    </Button>
                    <Button
                      variant="outlined"
                      disabled={busy}
                      onClick={() => void onArchive(selected.id)}
                    >
                      Delete
                    </Button>
                  </div>
                </div>
              </header>
              <CabinetSettingsPanel
                terminal={selected}
                unitName={siteName(selected.siteId)}
                onUnitSaved={() => {
                  void reload()
                }}
                onCabinetSaved={(updated) => {
                  setSelectedSiteId(updated.siteId)
                  setSelectedId(updated.id)
                  setSearchParams({ terminalId: updated.id }, { replace: true })
                  void reload()
                }}
              />
            </>
          ) : (
            <div className="cabinets-detail-empty">
              <h2>{detailEmpty.title}</h2>
              <p className="muted">{detailEmpty.body}</p>
            </div>
          )}
        </div>
      </div>

      {dialog}
    </section>
  )
}
