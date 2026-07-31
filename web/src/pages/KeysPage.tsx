import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { assignKeyToNextAvailableNode, countAvailableNodes } from '../api/keySlotAssignment'
import type { KeyDto, KeySlotDto, SiteDto, TerminalDto } from '../api/types'
import { Button, LinearProgress, SegmentedControl, useConfirm } from '../components/ui'

type SortDir = 'asc' | 'desc'
type KeysView = 'layout' | 'list'

type NodeCell = {
  nodeAddress: number
  key: KeyDto | null
  enrolled: boolean
  matchesFilter: boolean
}

function buildNodeAddresses(terminal: TerminalDto): number[] {
  const count = Math.max(1, Math.min(127, terminal.configuredSlotCount || 1))
  const rows = terminal.nodeRows != null && terminal.nodeRows > 0 ? terminal.nodeRows : null
  const perRow =
    terminal.nodesPerRow != null && terminal.nodesPerRow > 0 ? terminal.nodesPerRow : null
  if (rows && perRow) {
    const total = Math.min(count, rows * perRow)
    return Array.from({ length: total }, (_, i) => i + 1)
  }
  return Array.from({ length: count }, (_, i) => i + 1)
}

function nodesPerRowFor(terminal: TerminalDto): number {
  if (terminal.nodesPerRow != null && terminal.nodesPerRow > 0) return terminal.nodesPerRow
  const count = Math.max(1, terminal.configuredSlotCount || 1)
  if (count <= 6) return count
  if (count <= 12) return 6
  if (count <= 24) return 8
  return 10
}

function chunkIntoRows<T>(items: T[], perRow: number): T[][] {
  const rows: T[][] = []
  for (let i = 0; i < items.length; i += perRow) {
    rows.push(items.slice(i, i + perRow))
  }
  return rows
}

export function KeysPage() {
  const { confirmAction, dialog } = useConfirm()
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [keySlots, setKeySlots] = useState<KeySlotDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState('all')
  const [layoutTerminalId, setLayoutTerminalId] = useState('')
  const [view, setView] = useState<KeysView>('layout')
  const [selectedKeyId, setSelectedKeyId] = useState<string | null>(null)
  const [enrollFilter, setEnrollFilter] = useState<'all' | 'enrolled' | 'not-enrolled'>('all')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingKey, setEditingKey] = useState<KeyDto | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [siteId, setSiteId] = useState('')
  const [selectedTerminalId, setSelectedTerminalId] = useState('')
  const [availableNodes, setAvailableNodes] = useState<number | null>(null)

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [keyRows, siteRows, terminalRows, slotRows] = await Promise.all([
        api.listKeys(),
        api.listSites(),
        api.listTerminals(),
        api.listKeySlots(),
      ])
      setKeys(keyRows)
      setSites(siteRows)
      setTerminals(terminalRows)
      setKeySlots(slotRows)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load keys')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const terminalsForSelectedSite = useMemo(
    () => terminals.filter((t) => t.siteId === siteId),
    [terminals, siteId],
  )

  const layoutCabinets = useMemo(() => {
    if (siteFilter === 'all') return terminals
    return terminals.filter((t) => t.siteId === siteFilter)
  }, [terminals, siteFilter])

  useEffect(() => {
    if (!layoutCabinets.length) {
      setLayoutTerminalId('')
      return
    }
    if (!layoutCabinets.some((t) => t.id === layoutTerminalId)) {
      setLayoutTerminalId(layoutCabinets[0].id)
    }
  }, [layoutCabinets, layoutTerminalId])

  useEffect(() => {
    if (editingKey || !open) return
    if (terminalsForSelectedSite.length === 1) {
      setSelectedTerminalId(terminalsForSelectedSite[0].id)
      void countAvailableNodes(terminalsForSelectedSite[0]).then(setAvailableNodes)
    } else {
      setSelectedTerminalId('')
      setAvailableNodes(null)
    }
  }, [open, editingKey, terminalsForSelectedSite])

  function nodeLabelFor(key: KeyDto): string {
    const slot = keySlots.find((s) => s.managedKeyId === key.id)
    if (!slot) return 'Not assigned'
    const terminal = terminals.find((t) => t.id === slot.terminalId)
    return `Node ${slot.nodeAddress}` + (terminal ? ` (${terminal.name})` : '')
  }

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return keys
      .filter((k) => {
        const sn = sites.find((s) => s.id === k.siteId)?.name ?? ''
        const matchQ = !q || k.displayName.toLowerCase().includes(q) || sn.toLowerCase().includes(q)
        const matchSite = siteFilter === 'all' || k.siteId === siteFilter
        const enrolled = Boolean(k.fobEnrollmentReference)
        const matchEnroll =
          enrollFilter === 'all' || (enrollFilter === 'enrolled' ? enrolled : !enrolled)
        return matchQ && matchSite && matchEnroll
      })
      .sort((a, b) =>
        sortDir === 'asc'
          ? a.displayName.localeCompare(b.displayName)
          : b.displayName.localeCompare(a.displayName),
      )
  }, [keys, sites, query, siteFilter, enrollFilter, sortDir])

  const layoutTerminal = useMemo(
    () => layoutCabinets.find((t) => t.id === layoutTerminalId) ?? null,
    [layoutCabinets, layoutTerminalId],
  )

  const layoutCells: NodeCell[] = useMemo(() => {
    if (!layoutTerminal) return []
    const addresses = buildNodeAddresses(layoutTerminal)
    const q = query.trim().toLowerCase()
    return addresses.map((nodeAddress) => {
      const slot = keySlots.find(
        (s) => s.terminalId === layoutTerminal.id && s.nodeAddress === nodeAddress && s.managedKeyId,
      )
      const key = slot?.managedKeyId
        ? keys.find((k) => k.id === slot.managedKeyId) ?? null
        : null
      const enrolled = Boolean(key?.fobEnrollmentReference)
      let matchesFilter = true
      if (key) {
        if (q && !key.displayName.toLowerCase().includes(q)) matchesFilter = false
        if (enrollFilter === 'enrolled' && !enrolled) matchesFilter = false
        if (enrollFilter === 'not-enrolled' && enrolled) matchesFilter = false
      } else if (q || enrollFilter === 'enrolled') {
        // Free cells stay visible unless searching for a named key / enrolled-only.
        matchesFilter = !q && enrollFilter !== 'enrolled'
      }
      return { nodeAddress, key, enrolled, matchesFilter }
    })
  }, [layoutTerminal, keySlots, keys, query, enrollFilter])

  const layoutRows = useMemo(() => {
    if (!layoutTerminal) return []
    return chunkIntoRows(layoutCells, nodesPerRowFor(layoutTerminal))
  }, [layoutTerminal, layoutCells])

  const layoutStats = useMemo(() => {
    const assigned = layoutCells.filter((c) => c.key).length
    const enrolled = layoutCells.filter((c) => c.enrolled).length
    const free = layoutCells.length - assigned
    return { assigned, enrolled, free, total: layoutCells.length }
  }, [layoutCells])

  const selectedKey = useMemo(
    () => (selectedKeyId ? keys.find((k) => k.id === selectedKeyId) ?? null : null),
    [keys, selectedKeyId],
  )

  function openEdit(key: KeyDto) {
    setEditingKey(key)
    setDisplayName(key.displayName)
    setSiteId(key.siteId)
    setError(null)
    setOpen(true)
  }

  async function recycleKey(key: KeyDto) {
    if (!(await confirmAction({ message: 'Move key to Recycle Bin?', danger: true }))) return
    await api.deleteKey(key.id)
    if (selectedKeyId === key.id) setSelectedKeyId(null)
    await reload()
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      if (editingKey) {
        await api.updateKey(editingKey.id, {
          siteId,
          displayName: displayName.trim(),
          fobEnrollmentReference: editingKey.fobEnrollmentReference ?? null,
          expectedRevision: editingKey.revision,
        })
      } else {
        const created = await api.createKey({ siteId, displayName: displayName.trim() })
        const targetTerminal = terminalsForSelectedSite.find((t) => t.id === selectedTerminalId)
        if (targetTerminal) {
          const assignment = await assignKeyToNextAvailableNode(targetTerminal, created.id)
          if (!assignment.ok) {
            setError(
              assignment.reason === 'CAPACITY_FULL'
                ? `“${targetTerminal.name}” has no free key nodes left (${targetTerminal.configuredSlotCount} configured). The key was created but is not assigned to a cabinet slot.`
                : `Key was created, but assigning a cabinet node failed: ${assignment.message}`,
            )
          }
        } else if (terminalsForSelectedSite.length === 0) {
          const site = sites.find((s) => s.id === siteId)
          setError(
            `“${site?.name ?? 'This unit'}” has no cabinet registered yet — the key was created without a slot assignment.`,
          )
        }
      }
      setOpen(false)
      setEditingKey(null)
      setDisplayName('')
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This key was changed by someone else since you opened it. Reloading — please reapply.')
        setOpen(false)
        setEditingKey(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save key')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Key Settings</h1>
          <p className="muted">
            Layout shows cabinet nodes; List is the searchable table. Raw NFC UIDs never appear here.
          </p>
        </div>
        <Button
          icon={Plus}
          onClick={() => {
            setEditingKey(null)
            setDisplayName('')
            setOpen(true)
          }}
          disabled={!sites.length}
        >
          Add key
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading keys" />}

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search key name or unit…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select
          value={siteFilter}
          onChange={(e) => setSiteFilter(e.target.value)}
          title="Filter by unit"
        >
          <option value="all">All units</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>
        {view === 'layout' && (
          <select
            value={layoutTerminalId}
            onChange={(e) => {
              setLayoutTerminalId(e.target.value)
              setSelectedKeyId(null)
            }}
            title="Cabinet for layout"
            disabled={!layoutCabinets.length}
          >
            {!layoutCabinets.length && <option value="">No cabinets</option>}
            {layoutCabinets.map((t) => (
              <option key={t.id} value={t.id}>
                {t.name}
                {siteFilter === 'all'
                  ? ` · ${sites.find((s) => s.id === t.siteId)?.name ?? 'Unit'}`
                  : ''}
              </option>
            ))}
          </select>
        )}
        <select
          value={enrollFilter}
          onChange={(e) => setEnrollFilter(e.target.value as 'all' | 'enrolled' | 'not-enrolled')}
          title="Filter by enrollment"
        >
          <option value="all">All enrollment</option>
          <option value="enrolled">Enrolled</option>
          <option value="not-enrolled">Not enrolled</option>
        </select>
        {view === 'list' && (
          <Button variant="outlined" onClick={() => setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))}>
            Name {sortDir === 'asc' ? '↑' : '↓'}
          </Button>
        )}
        <SegmentedControl<KeysView>
          ariaLabel="Keys view"
          value={view}
          onChange={setView}
          options={[
            { value: 'layout', label: 'Layout' },
            { value: 'list', label: 'List' },
          ]}
        />
      </div>

      {view === 'layout' ? (
        !layoutTerminal ? (
          !busy && (
            <div className="empty-state">
              {siteFilter === 'all'
                ? 'No cabinets registered yet. Register a key cabinet first.'
                : 'No cabinet for this unit. Register a cabinet or pick another unit.'}
            </div>
          )
        ) : (
          <div className="keys-layout-wrap">
            <div className="keys-cabinet">
              <header className="keys-cabinet-header">
                <div>
                  <p className="keys-cabinet-eyebrow">Key cabinet layout</p>
                  <h2 className="keys-cabinet-title">{layoutTerminal.name}</h2>
                  <p className="keys-cabinet-meta muted">
                    {sites.find((s) => s.id === layoutTerminal.siteId)?.name ?? 'Unit'}
                    {layoutTerminal.nodeRows && layoutTerminal.nodesPerRow
                      ? ` · ${layoutTerminal.nodeRows} rows × ${layoutTerminal.nodesPerRow} nodes`
                      : ` · ${layoutTerminal.configuredSlotCount} nodes`}
                  </p>
                </div>
                <div className="keys-cabinet-stats" aria-label="Cabinet occupancy">
                  <span className="keys-stat">
                    <strong>{layoutStats.assigned}</strong> assigned
                  </span>
                  <span className="keys-stat keys-stat-free">
                    <strong>{layoutStats.free}</strong> free
                  </span>
                  <span className="keys-stat keys-stat-enrolled">
                    <strong>{layoutStats.enrolled}</strong> enrolled
                  </span>
                </div>
              </header>

              <div className="keys-cabinet-legend" aria-hidden="true">
                <span>
                  <i className="keys-legend-swatch free" /> Free
                </span>
                <span>
                  <i className="keys-legend-swatch assigned" /> Assigned
                </span>
                <span>
                  <i className="keys-legend-swatch enrolled" /> Fob enrolled
                </span>
              </div>

              <div className="keys-cabinet-face">
                {layoutRows.map((row, rowIndex) => (
                  <div className="keys-cabinet-bay" key={`bay-${rowIndex}`}>
                    <span className="keys-bay-label">Row {rowIndex + 1}</span>
                    <div
                      className="keys-bay-slots"
                      style={{
                        gridTemplateColumns: `repeat(${row.length}, minmax(72px, 1fr))`,
                      }}
                    >
                      {row.map((cell) => {
                        const selected = cell.key != null && cell.key.id === selectedKeyId
                        const occupied = cell.key != null
                        return (
                          <button
                            key={cell.nodeAddress}
                            type="button"
                            className={[
                              'keys-slot',
                              occupied ? 'assigned' : 'free',
                              cell.enrolled ? 'enrolled' : '',
                              selected ? 'selected' : '',
                              cell.matchesFilter ? '' : 'dimmed',
                            ]
                              .filter(Boolean)
                              .join(' ')}
                            disabled={!occupied}
                            title={
                              occupied
                                ? `${cell.key!.displayName} · Node ${cell.nodeAddress}`
                                : `Free · Node ${cell.nodeAddress}`
                            }
                            onClick={() => {
                              if (cell.key) setSelectedKeyId(cell.key.id)
                            }}
                          >
                            <span className="keys-slot-ring" aria-hidden="true">
                              {cell.nodeAddress}
                            </span>
                            <span className="keys-slot-label">
                              {cell.key ? cell.key.displayName : 'Free'}
                            </span>
                            {cell.enrolled && <span className="keys-slot-dot" title="Enrolled" />}
                          </button>
                        )
                      })}
                    </div>
                  </div>
                ))}
              </div>
            </div>

            <aside className="keys-inspector">
              {selectedKey ? (
                <>
                  <p className="keys-cabinet-eyebrow">Selected key</p>
                  <h3 className="keys-inspector-title">{selectedKey.displayName}</h3>
                  <dl className="keys-inspector-fields">
                    <div>
                      <dt>Unit</dt>
                      <dd>{sites.find((s) => s.id === selectedKey.siteId)?.name ?? '—'}</dd>
                    </div>
                    <div>
                      <dt>Cabinet node</dt>
                      <dd>{nodeLabelFor(selectedKey)}</dd>
                    </div>
                    <div>
                      <dt>Enrollment</dt>
                      <dd>
                        {selectedKey.fobEnrollmentReference ? (
                          <span className="badge badge-success">Enrolled on terminal</span>
                        ) : (
                          <span className="muted">Not enrolled (terminal-local)</span>
                        )}
                      </dd>
                    </div>
                  </dl>
                  <div className="keys-inspector-actions">
                    <Button onClick={() => openEdit(selectedKey)}>Edit</Button>
                    <Button variant="outlined" onClick={() => void recycleKey(selectedKey)}>
                      Recycle
                    </Button>
                  </div>
                </>
              ) : (
                <>
                  <p className="keys-cabinet-eyebrow">Selected key</p>
                  <h3 className="keys-inspector-title">None</h3>
                  <p className="muted" style={{ margin: 0 }}>
                    Tap an assigned slot on the cabinet face. Free slots fill when you use{' '}
                    <strong>Add key</strong> (next free node is assigned automatically).
                  </p>
                </>
              )}
            </aside>
          </div>
        )
      ) : filtered.length ? (
        <div className="data-panel">
          <table className="data-table compact">
            <thead>
              <tr>
                <th>Key</th>
                <th>Unit</th>
                <th>Cabinet node</th>
                <th>Enrollment</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((key) => (
                <tr key={key.id}>
                  <td className="cell-title">{key.displayName}</td>
                  <td>{sites.find((s) => s.id === key.siteId)?.name ?? '—'}</td>
                  <td>{nodeLabelFor(key)}</td>
                  <td>
                    {key.fobEnrollmentReference ? (
                      <span className="badge badge-success">Enrolled</span>
                    ) : (
                      <span className="muted">Not enrolled</span>
                    )}
                  </td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(key)}>
                        Edit
                      </Button>
                      <Button variant="link" onClick={() => void recycleKey(key)}>
                        Recycle
                      </Button>
                    </div>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No keys match current filters.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingKey ? 'Edit key' : 'Add key'}</h2>
            <p className="dialog-copy">
              Create a managed key record without exposing NFC secrets or biometric material.
            </p>
            <div className="field">
              <label>Key name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </div>
            {!editingKey && terminalsForSelectedSite.length > 1 && (
              <div className="field">
                <label>Cabinet</label>
                <select
                  value={selectedTerminalId}
                  onChange={(e) => {
                    setSelectedTerminalId(e.target.value)
                    const t = terminalsForSelectedSite.find((x) => x.id === e.target.value)
                    if (t) void countAvailableNodes(t).then(setAvailableNodes)
                  }}
                  required
                >
                  <option value="" disabled>
                    Select the cabinet this key&apos;s node will be assigned in
                  </option>
                  {terminalsForSelectedSite.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name} (Box {t.boxAddress})
                    </option>
                  ))}
                </select>
              </div>
            )}
            {!editingKey && terminalsForSelectedSite.length === 0 && (
              <p className="dialog-copy muted">
                No cabinet is registered for this unit yet — the key will be created without a node
                assignment.
              </p>
            )}
            {!editingKey && selectedTerminalId && availableNodes != null && (
              <p className={availableNodes === 0 ? 'dialog-copy error-banner' : 'dialog-copy muted'}>
                {availableNodes === 0
                  ? 'This cabinet has no free key nodes left — add more capacity before adding another key here.'
                  : `${availableNodes} free key node(s) on the selected cabinet.`}
              </p>
            )}
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingKey(null)
                }}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                icon={Check}
                loading={busy}
                disabled={!editingKey && terminalsForSelectedSite.length === 1 && availableNodes === 0}
              >
                {editingKey ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
