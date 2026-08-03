import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { assignKeyToNextAvailableNode, assignKeyToNode, countAvailableNodes, listFreeNodeAddresses } from '../api/keySlotAssignment'
import { layoutFieldsFromTerminal, parseCabinetLayout } from '../api/cabinetLayout'
import { CabinetLayoutFields } from '../components/CabinetLayoutFields'
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

export function KeysPage({
  lockedSiteId,
  lockedTerminalId,
  embedded = false,
}: {
  lockedSiteId?: string
  /** When set (Cabinet Management), layout/add target this cabinet. */
  lockedTerminalId?: string
  embedded?: boolean
} = {}) {
  const { confirmAction, dialog } = useConfirm()
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [keySlots, setKeySlots] = useState<KeySlotDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState(lockedSiteId ?? 'all')
  const [layoutTerminalId, setLayoutTerminalId] = useState(lockedTerminalId ?? '')
  const [view, setView] = useState<KeysView>('layout')
  const [selectedKeyId, setSelectedKeyId] = useState<string | null>(null)
  const [selectedNodeAddress, setSelectedNodeAddress] = useState<number | null>(null)
  const [enrollFilter, setEnrollFilter] = useState<'all' | 'enrolled' | 'not-enrolled'>('all')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingKey, setEditingKey] = useState<KeyDto | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [siteId, setSiteId] = useState(lockedSiteId ?? '')
  const [selectedTerminalId, setSelectedTerminalId] = useState(lockedTerminalId ?? '')
  const [targetNodeAddress, setTargetNodeAddress] = useState('')
  const [availableNodes, setAvailableNodes] = useState<number | null>(null)
  const [layoutOpen, setLayoutOpen] = useState(false)
  const [editLayoutRows, setEditLayoutRows] = useState('3')
  const [editLayoutColumns, setEditLayoutColumns] = useState('8')

  useEffect(() => {
    if (!lockedSiteId) return
    setSiteFilter(lockedSiteId)
    setSiteId(lockedSiteId)
  }, [lockedSiteId])

  useEffect(() => {
    if (!lockedTerminalId) return
    setLayoutTerminalId(lockedTerminalId)
    setSelectedTerminalId(lockedTerminalId)
  }, [lockedTerminalId])

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
      if (lockedSiteId) {
        setSiteId(lockedSiteId)
      } else if (!siteId && siteRows[0]) {
        setSiteId(siteRows[0].id)
      }
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
    if (lockedTerminalId) {
      return terminals.filter((t) => t.id === lockedTerminalId)
    }
    if (siteFilter === 'all') return terminals
    return terminals.filter((t) => t.siteId === siteFilter)
  }, [terminals, siteFilter, lockedTerminalId])

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
    if (lockedTerminalId) {
      setSelectedTerminalId(lockedTerminalId)
      const terminal = terminals.find((t) => t.id === lockedTerminalId)
      if (terminal) void countAvailableNodes(terminal).then(setAvailableNodes)
      else setAvailableNodes(null)
      return
    }
    if (terminalsForSelectedSite.length === 1) {
      setSelectedTerminalId(terminalsForSelectedSite[0].id)
      void countAvailableNodes(terminalsForSelectedSite[0]).then(setAvailableNodes)
    } else {
      setSelectedTerminalId('')
      setAvailableNodes(null)
    }
  }, [open, editingKey, terminalsForSelectedSite, lockedTerminalId, terminals])

  function nodeLabelFor(key: KeyDto): string {
    const slot = keySlots.find((s) => s.managedKeyId === key.id)
    if (!slot) return 'Not assigned'
    const terminal = terminals.find((t) => t.id === slot.terminalId)
    return `Slot ${slot.nodeAddress}` + (terminal ? ` (${terminal.name})` : '')
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

  const freeNodesForDialog = useMemo(() => {
    const terminal =
      terminals.find((t) => t.id === (editingKey
        ? keySlots.find((s) => s.managedKeyId === editingKey.id)?.terminalId ?? selectedTerminalId
        : selectedTerminalId)) ?? null
    if (!terminal) return [] as number[]
    const free = listFreeNodeAddresses(terminal, keySlots)
    if (editingKey) {
      const current = keySlots.find((s) => s.managedKeyId === editingKey.id)
      if (current && !free.includes(current.nodeAddress)) {
        return [...free, current.nodeAddress].sort((a, b) => a - b)
      }
    }
    return free
  }, [terminals, selectedTerminalId, keySlots, editingKey])

  function openAddDialog(nodeAddress?: number) {
    setEditingKey(null)
    setDisplayName('')
    setError(null)
    if (lockedSiteId) setSiteId(lockedSiteId)
    if (lockedTerminalId) setSelectedTerminalId(lockedTerminalId)
    else if (layoutTerminalId) setSelectedTerminalId(layoutTerminalId)
    setTargetNodeAddress(nodeAddress != null ? String(nodeAddress) : '')
    setOpen(true)
  }

  function openEdit(key: KeyDto) {
    setEditingKey(key)
    setDisplayName(key.displayName)
    setSiteId(key.siteId)
    const slot = keySlots.find((s) => s.managedKeyId === key.id)
    if (slot) {
      setSelectedTerminalId(slot.terminalId)
      setTargetNodeAddress(String(slot.nodeAddress))
    } else {
      setTargetNodeAddress('')
    }
    setError(null)
    setOpen(true)
  }

  function selectCell(cell: NodeCell) {
    setSelectedNodeAddress(cell.nodeAddress)
    setSelectedKeyId(cell.key?.id ?? null)
  }

  async function recycleKey(key: KeyDto) {
    if (
      !(await confirmAction({
        message: 'Delete this key? It will move to Deleted items and can be restored for 60 days.',
        danger: true,
      }))
    ) {
      return
    }
    setBusy(true)
    setError(null)
    try {
      await api.deleteKey(key.id)
      if (selectedKeyId === key.id) setSelectedKeyId(null)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to delete key')
    } finally {
      setBusy(false)
    }
  }

  function openLayoutEditor() {
    if (!layoutTerminal) {
      setError('Select a cabinet before editing layout.')
      return
    }
    const fields = layoutFieldsFromTerminal(layoutTerminal)
    setEditLayoutRows(fields.rows)
    setEditLayoutColumns(fields.columns)
    setError(null)
    setLayoutOpen(true)
  }

  async function onSaveLayout(e: FormEvent) {
    e.preventDefault()
    if (!layoutTerminal) return
    const layout = parseCabinetLayout(editLayoutRows, editLayoutColumns)
    if (!layout.ok) {
      setError(layout.message)
      return
    }
    const occupied = keySlots.filter(
      (s) => s.terminalId === layoutTerminal.id && s.managedKeyId && s.nodeAddress > layout.value.totalSlots,
    )
    if (occupied.length) {
      setError(
        `Cannot shrink to ${layout.value.totalSlots} slots — ${occupied.length} key(s) are assigned above that. Move or delete those keys first.`,
      )
      return
    }
    setBusy(true)
    setError(null)
    try {
      const updated = await api.updateTerminal(layoutTerminal.id, {
        siteId: layoutTerminal.siteId,
        name: layoutTerminal.name,
        boxAddress: layoutTerminal.boxAddress,
        serialNumber: layoutTerminal.serialNumber ?? null,
        configuredSlotCount: layout.value.totalSlots,
        vendorDeviceId: layoutTerminal.vendorDeviceId ?? null,
        nodeRows: layout.value.rows,
        nodesPerRow: layout.value.columns,
        latitude: layoutTerminal.latitude ?? null,
        longitude: layoutTerminal.longitude ?? null,
        expectedRevision: layoutTerminal.revision,
      })
      setTerminals((prev) => prev.map((t) => (t.id === updated.id ? updated : t)))
      setLayoutOpen(false)
      setSelectedNodeAddress(null)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This cabinet was changed by someone else. Reloading — please reapply layout.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save layout')
      }
    } finally {
      setBusy(false)
    }
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const preferredNode = targetNodeAddress ? Number(targetNodeAddress) : null
      if (editingKey) {
        await api.updateKey(editingKey.id, {
          siteId,
          displayName: displayName.trim(),
          fobEnrollmentReference: editingKey.fobEnrollmentReference ?? null,
          expectedRevision: editingKey.revision,
        })
        const slot = keySlots.find((s) => s.managedKeyId === editingKey.id)
        const terminalId = slot?.terminalId ?? selectedTerminalId
        const terminal = terminals.find((t) => t.id === terminalId)
        if (terminal && preferredNode != null && Number.isFinite(preferredNode)) {
          if (slot?.nodeAddress !== preferredNode) {
            const assignment = await assignKeyToNode(terminal, editingKey.id, preferredNode)
            if (!assignment.ok) {
              setError(
                assignment.reason === 'NODE_TAKEN'
                  ? `Slot ${preferredNode} is already occupied.`
                  : assignment.reason === 'ERROR'
                    ? assignment.message
                    : `Could not move key to slot ${preferredNode}.`,
              )
              setBusy(false)
              return
            }
          }
        }
      } else {
        const created = await api.createKey({ siteId, displayName: displayName.trim() })
        const targetTerminal =
          terminals.find((t) => t.id === selectedTerminalId) ??
          terminalsForSelectedSite.find((t) => t.id === selectedTerminalId)
        if (targetTerminal) {
          const assignment =
            preferredNode != null && Number.isFinite(preferredNode)
              ? await assignKeyToNode(targetTerminal, created.id, preferredNode)
              : await assignKeyToNextAvailableNode(targetTerminal, created.id)
          if (!assignment.ok) {
            setError(
              assignment.reason === 'CAPACITY_FULL'
                ? `“${targetTerminal.name}” has no free key slots left. Use Edit layout to add more, then try again.`
                : assignment.reason === 'NODE_TAKEN'
                  ? `Slot ${preferredNode} is already occupied.`
                  : assignment.reason === 'ERROR'
                    ? assignment.message
                    : `Key was created, but assigning a cabinet slot failed.`,
            )
            await reload()
            setBusy(false)
            return
          }
        } else if (terminalsForSelectedSite.length === 0) {
          const site = sites.find((s) => s.id === siteId)
          setError(
            `“${site?.name ?? 'This location'}” has no cabinet registered yet — the key was created without a slot assignment.`,
          )
        }
      }
      setOpen(false)
      setEditingKey(null)
      setDisplayName('')
      setTargetNodeAddress('')
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
      {!embedded && (
        <div className="page-header">
          <div>
            <h1>Key Settings</h1>
            <p className="muted">
              Set rows × columns with <strong>Edit layout</strong>. Click a free slot to add a key, or an
              assigned slot to edit/delete.
            </p>
          </div>
          <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
            <Button variant="tonal" onClick={openLayoutEditor} disabled={!layoutCabinets.length}>
              Edit layout
            </Button>
            <Button icon={Plus} onClick={() => openAddDialog()} disabled={!sites.length}>
              Add key
            </Button>
          </div>
        </div>
      )}

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading keys" />}

      <div className="toolbar-row">
        {embedded && (
          <>
            <Button variant="tonal" onClick={openLayoutEditor} disabled={!layoutCabinets.length}>
              Edit layout
            </Button>
            <Button icon={Plus} onClick={() => openAddDialog()} disabled={!sites.length}>
              Add key
            </Button>
          </>
        )}
        <input
          className="search"
          placeholder={lockedSiteId ? 'Search key name…' : 'Search key name or location…'}
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        {!lockedSiteId && (
          <select
            value={siteFilter}
            onChange={(e) => setSiteFilter(e.target.value)}
            title="Filter by location"
          >
            <option value="all">All locations</option>
            {sites.map((s) => (
              <option key={s.id} value={s.id}>
                {s.name}
              </option>
            ))}
          </select>
        )}
        {view === 'layout' && !lockedTerminalId && (
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
                {!lockedSiteId && siteFilter === 'all'
                  ? ` · ${sites.find((s) => s.id === t.siteId)?.name ?? 'Location'}`
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
                : 'No cabinet for this location. Register a cabinet or pick another location.'}
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
                    {sites.find((s) => s.id === layoutTerminal.siteId)?.name ?? 'Location'}
                    {layoutTerminal.nodeRows && layoutTerminal.nodesPerRow
                      ? ` · ${layoutTerminal.nodeRows} rows × ${layoutTerminal.nodesPerRow} columns`
                      : ` · ${layoutTerminal.configuredSlotCount} key slots`}
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
                        const selected =
                          selectedNodeAddress === cell.nodeAddress ||
                          (cell.key != null && cell.key.id === selectedKeyId)
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
                            title={
                              occupied
                                ? `${cell.key!.displayName} · Slot ${cell.nodeAddress}`
                                : `Free · Slot ${cell.nodeAddress} — click to add or manage`
                            }
                            onClick={() => selectCell(cell)}
                            onDoubleClick={() => {
                              if (occupied && cell.key) openEdit(cell.key)
                              else openAddDialog(cell.nodeAddress)
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
                  <p className="keys-cabinet-eyebrow">Selected key slot</p>
                  <h3 className="keys-inspector-title">{selectedKey.displayName}</h3>
                  <dl className="keys-inspector-fields">
                    <div>
                      <dt>Location</dt>
                      <dd>{sites.find((s) => s.id === selectedKey.siteId)?.name ?? '—'}</dd>
                    </div>
                    <div>
                      <dt>Cabinet slot</dt>
                      <dd>{nodeLabelFor(selectedKey)}</dd>
                    </div>
                    <div>
                      <dt>Enrollment</dt>
                      <dd>
                        {selectedKey.fobEnrollmentReference ? (
                          <span className="badge badge-success">Enrolled on cabinet</span>
                        ) : (
                          <span className="muted">Not enrolled (stored on cabinet only)</span>
                        )}
                      </dd>
                    </div>
                  </dl>
                  <div className="keys-inspector-actions">
                    <Button onClick={() => openEdit(selectedKey)}>Edit</Button>
                    <Button variant="outlined" onClick={() => void recycleKey(selectedKey)}>
                      Delete
                    </Button>
                  </div>
                </>
              ) : selectedNodeAddress != null && layoutTerminal ? (
                <>
                  <p className="keys-cabinet-eyebrow">Selected key slot</p>
                  <h3 className="keys-inspector-title">Slot {selectedNodeAddress}</h3>
                  <p className="muted" style={{ margin: '0 0 12px' }}>
                    Free — add a key to this slot. Change rows × columns with <strong>Edit layout</strong>.
                  </p>
                  <div className="keys-inspector-actions">
                    <Button onClick={() => openAddDialog(selectedNodeAddress)}>Add key here</Button>
                    <Button variant="tonal" onClick={openLayoutEditor}>
                      Edit layout
                    </Button>
                  </div>
                </>
              ) : (
                <>
                  <p className="keys-cabinet-eyebrow">Selected key slot</p>
                  <h3 className="keys-inspector-title">None</h3>
                  <p className="muted" style={{ margin: 0 }}>
                    Select a free slot to add a key, or an assigned slot to edit/delete. Use{' '}
                    <strong>Edit layout</strong> to set rows and columns.
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
                <th>Location</th>
                <th>Cabinet slot</th>
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
                        Delete
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
            <h2>{editingKey ? 'Edit key slot' : 'Add key to slot'}</h2>
            <p className="dialog-copy">
              Manage the key name and which cabinet slot it occupies. Raw NFC secrets are never shown
              here.
            </p>
            <div className="field">
              <label>Key name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Location</label>
              <select
                value={siteId}
                onChange={(e) => setSiteId(e.target.value)}
                required
                disabled={Boolean(lockedSiteId)}
              >
                {sites
                  .filter((s) => !lockedSiteId || s.id === lockedSiteId)
                  .map((s) => (
                    <option key={s.id} value={s.id}>
                      {s.name}
                    </option>
                  ))}
              </select>
            </div>
            {!editingKey && terminalsForSelectedSite.length > 1 && !lockedTerminalId && (
              <div className="field">
                <label>Cabinet</label>
                <select
                  value={selectedTerminalId}
                  onChange={(e) => {
                    setSelectedTerminalId(e.target.value)
                    setTargetNodeAddress('')
                    const t = terminalsForSelectedSite.find((x) => x.id === e.target.value)
                    if (t) void countAvailableNodes(t).then(setAvailableNodes)
                  }}
                  required
                >
                  <option value="" disabled>
                    Select the cabinet for this key slot
                  </option>
                  {terminalsForSelectedSite.map((t) => (
                    <option key={t.id} value={t.id}>
                      {t.name} (Cabinet number {t.boxAddress})
                    </option>
                  ))}
                </select>
              </div>
            )}
            {(editingKey || selectedTerminalId) && (
              <div className="field">
                <label>Key slot number</label>
                <select
                  value={targetNodeAddress}
                  onChange={(e) => setTargetNodeAddress(e.target.value)}
                  required={!editingKey}
                >
                  {!editingKey && <option value="">Next free slot</option>}
                  {freeNodesForDialog.map((node) => (
                    <option key={node} value={String(node)}>
                      Slot {node}
                    </option>
                  ))}
                </select>
              </div>
            )}
            {!editingKey && terminalsForSelectedSite.length === 0 && (
              <p className="dialog-copy muted">
                No cabinet is registered for this location yet — the key will be created without a
                slot assignment.
              </p>
            )}
            {!editingKey && selectedTerminalId && availableNodes != null && (
              <p className={availableNodes === 0 ? 'dialog-copy error-banner' : 'dialog-copy muted'}>
                {availableNodes === 0
                  ? 'This cabinet has no free key slots — use Edit layout to add more.'
                  : `${availableNodes} free key slot(s) on the selected cabinet.`}
              </p>
            )}
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingKey(null)
                  setTargetNodeAddress('')
                }}
              >
                Cancel
              </Button>
              <Button
                type="submit"
                icon={Check}
                loading={busy}
                disabled={!editingKey && availableNodes === 0}
              >
                {editingKey ? 'Save changes' : 'Save'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {layoutOpen && layoutTerminal && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={(e) => void onSaveLayout(e)}>
            <h2>Edit cabinet layout</h2>
            <p className="dialog-copy">
              Set rows and columns for <strong>{layoutTerminal.name}</strong>. Total key slots update
              automatically. Keys already assigned above the new total must be moved or deleted first.
            </p>
            <CabinetLayoutFields
              rows={editLayoutRows}
              columns={editLayoutColumns}
              onRowsChange={setEditLayoutRows}
              onColumnsChange={setEditLayoutColumns}
            />
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setLayoutOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                Save layout
              </Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
