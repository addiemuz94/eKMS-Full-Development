import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto, TerminalDto } from '../api/types'
import { CabinetSettingsPanel } from '../components/CabinetSettingsPanel'
import { Button, LinearProgress, useConfirm } from '../components/ui'

type PairingBanner = {
  code: string
  expiresAtEpochMillis: number
  terminalName: string
  terminalId: string
  regenerated?: boolean
}

export function TerminalsPage() {
  const { confirmAction, dialog } = useConfirm()
  const [sites, setSites] = useState<SiteDto[]>([])
  const [terminals, setTerminals] = useState<TerminalDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState('all')
  const [pairedFilter, setPairedFilter] = useState<'all' | 'paired' | 'unpaired'>('all')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingTerminal, setEditingTerminal] = useState<TerminalDto | null>(null)
  const [settingsTerminal, setSettingsTerminal] = useState<TerminalDto | null>(null)
  const [pairing, setPairing] = useState<PairingBanner | null>(null)

  const [name, setName] = useState('')
  const [siteId, setSiteId] = useState('')
  const [boxAddress, setBoxAddress] = useState('1')
  const [serialNumber, setSerialNumber] = useState('')
  const [nodeCount, setNodeCount] = useState('24')
  const [vendorDeviceId, setVendorDeviceId] = useState('')
  const [nodeRows, setNodeRows] = useState('')
  const [nodesPerRow, setNodesPerRow] = useState('')
  const [latitude, setLatitude] = useState('')
  const [longitude, setLongitude] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [siteRows, terminalRows] = await Promise.all([api.listSites(), api.listTerminals()])
      setSites(siteRows)
      setTerminals(terminalRows)
      if (!siteId && siteRows[0]) setSiteId(siteRows[0].id)
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load terminals')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const visible = useMemo(() => {
    const q = query.trim().toLowerCase()
    return terminals.filter((t) => {
      const sn = sites.find((s) => s.id === t.siteId)?.name ?? ''
      const matchQ = !q || t.name.toLowerCase().includes(q) || sn.toLowerCase().includes(q)
      const matchSite = siteFilter === 'all' || t.siteId === siteFilter
      const matchPaired = pairedFilter === 'all' || (pairedFilter === 'paired' ? t.paired : !t.paired)
      return matchQ && matchSite && matchPaired
    })
  }, [terminals, sites, query, siteFilter, pairedFilter])

  function siteName(id: string) {
    return sites.find((site) => site.id === id)?.name ?? 'Unassigned unit'
  }

  function resetForm() {
    setName('')
    setBoxAddress('1')
    setSerialNumber('')
    setNodeCount('24')
    setVendorDeviceId('')
    setNodeRows('')
    setNodesPerRow('')
    setLatitude('')
    setLongitude('')
  }

  function openCreate() {
    setEditingTerminal(null)
    resetForm()
    if (sites[0]) setSiteId(sites[0].id)
    setError(null)
    setOpen(true)
  }

  function openEdit(terminal: TerminalDto) {
    setEditingTerminal(terminal)
    setName(terminal.name)
    setSiteId(terminal.siteId)
    setBoxAddress(String(terminal.boxAddress))
    setSerialNumber(terminal.serialNumber ?? '')
    setNodeCount(String(terminal.configuredSlotCount))
    setVendorDeviceId(terminal.vendorDeviceId ?? '')
    setNodeRows(terminal.nodeRows != null ? String(terminal.nodeRows) : '')
    setNodesPerRow(terminal.nodesPerRow != null ? String(terminal.nodesPerRow) : '')
    setLatitude(terminal.latitude != null ? String(terminal.latitude) : '')
    setLongitude(terminal.longitude != null ? String(terminal.longitude) : '')
    setError(null)
    setOpen(true)
  }

  function buildPayload(forUpdate: boolean) {
    const slots = Math.min(127, Math.max(1, Number(nodeCount) || 24))
    const payload: Record<string, unknown> = {
      siteId,
      name: name.trim() || 'New Cabinet',
      boxAddress: Math.max(1, Number(boxAddress) || 1),
      serialNumber: serialNumber.trim() || null,
      configuredSlotCount: slots,
      vendorDeviceId: vendorDeviceId.trim() || null,
      nodeRows: nodeRows.trim() ? Number(nodeRows) : null,
      nodesPerRow: nodesPerRow.trim() ? Number(nodesPerRow) : null,
      latitude: latitude.trim() ? Number(latitude) : null,
      longitude: longitude.trim() ? Number(longitude) : null,
    }
    if (forUpdate && editingTerminal) {
      payload.expectedRevision = editingTerminal.revision
    }
    return payload
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!siteId) {
      setError('Select a unit before registering a key cabinet.')
      return
    }
    const slots = Number(nodeCount)
    if (!Number.isFinite(slots) || slots < 1 || slots > 127) {
      setError('Configured node count must be between 1 and 127.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      if (editingTerminal) {
        await api.updateTerminal(editingTerminal.id, buildPayload(true))
        setOpen(false)
        setEditingTerminal(null)
        resetForm()
        await reload()
      } else {
        const result = await api.createTerminal(buildPayload(false))
        setOpen(false)
        resetForm()
        setPairing({
          code: result.pairingCode,
          expiresAtEpochMillis: result.pairingCodeExpiresAtEpochMillis,
          terminalName: result.terminal.name,
          terminalId: result.terminal.id,
        })
        await reload()
      }
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This terminal was changed by someone else since you opened it. Reloading the latest version — please reapply your edit.',
        )
        setOpen(false)
        setEditingTerminal(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save terminal')
      }
    } finally {
      setBusy(false)
    }
  }

  async function onRegenerate(terminal: TerminalDto) {
    if (
      !(await confirmAction({
        message:
          'Regenerate pairing code? This immediately revokes any existing device session for this cabinet. Give the new code only to the on-site technician.',
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
      setError(err instanceof ApiError ? err.message : 'Failed to regenerate pairing code')
    } finally {
      setBusy(false)
    }
  }

  async function onArchive(id: string) {
    if (!(await confirmAction({ message: 'Move this terminal to the Recycle Bin?', danger: true }))) return
    setBusy(true)
    setError(null)
    try {
      await api.deleteTerminal(id)
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to archive terminal')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Terminals</h1>
          <p className="muted">
            Hub for key cabinet registration and management: edit cabinet details, behavioral settings,
            pairing codes, and per-terminal data sync. Use Registration under Home for new-unit onboarding.
          </p>
        </div>
        <Button icon={Plus} onClick={openCreate} disabled={!sites.length}>
          Register key cabinet
        </Button>
      </div>

      {error && <div className="error-banner">{error}</div>}
      {busy && <LinearProgress className="table-busy" label="Loading terminals" />}

      {pairing && (
        <div className="notice pairing-code-banner" role="status">
          <h3>{pairing.regenerated ? 'New pairing code' : 'Pairing code — copy now'}</h3>
          <p className="muted">
            For <strong>{pairing.terminalName}</strong>. Shown once — the server only stores a hash and
            cannot show this code again. Expires{' '}
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

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search cabinet or unit…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select value={siteFilter} onChange={(e) => setSiteFilter(e.target.value)} title="Filter by unit">
          <option value="all">All units</option>
          {sites.map((site) => (
            <option key={site.id} value={site.id}>{site.name}</option>
          ))}
        </select>
        <select value={pairedFilter} onChange={(e) => setPairedFilter(e.target.value as 'all' | 'paired' | 'unpaired')} title="Pairing status">
          <option value="all">All statuses</option>
          <option value="paired">Paired</option>
          <option value="unpaired">Not paired</option>
        </select>
      </div>

      {visible.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Cabinet</th>
                <th>Unit</th>
                <th>Layout</th>
                <th>Paired</th>
                <th>Key Cabinet ID</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {visible.map((terminal) => (
                <tr key={terminal.id}>
                  <td className="cell-title">{terminal.name}</td>
                  <td>{siteName(terminal.siteId)}</td>
                  <td>
                    Box {terminal.boxAddress} · {terminal.configuredSlotCount} nodes
                    {terminal.nodeRows && terminal.nodesPerRow
                      ? ` · ${terminal.nodeRows}×${terminal.nodesPerRow}`
                      : ''}
                  </td>
                  <td>
                    <span className={`badge${terminal.paired ? ' badge-success' : ''}`}>
                      {terminal.paired ? 'Paired' : 'Not paired'}
                    </span>
                  </td>
                  <td className="mono">{terminal.id}</td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(terminal)}>
                        Edit
                      </Button>
                      <Button variant="link" onClick={() => setSettingsTerminal(terminal)}>
                        Settings
                      </Button>
                      <Button variant="link" disabled={busy} onClick={() => void onRegenerate(terminal)}>
                        Pairing code
                      </Button>
                      <Link className="btn linkish" to={`/data-sync?terminalId=${terminal.id}`}>
                        Data sync
                      </Link>
                      <Button variant="link" disabled={busy} onClick={() => void onArchive(terminal.id)}>
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
        !busy && <div className="empty-state">No key cabinets yet. Register one for a unit first.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingTerminal ? 'Edit key cabinet' : 'Register key cabinet'}</h2>
            <p className="dialog-copy">
              {editingTerminal
                ? 'Update cabinet configuration. Use Pairing code on the list to issue a new code for the technician.'
                : 'After save, a 6-digit pairing code is shown once for the on-site technician.'}
            </p>
            <div className="field">
              <label>Cabinet name</label>
              <input value={name} onChange={(e) => setName(e.target.value)} required placeholder="Johor HQ Cabinet" />
            </div>
            <div className="field">
              <label>Unit (site)</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="split">
              <div className="field">
                <label>Box address</label>
                <input value={boxAddress} onChange={(e) => setBoxAddress(e.target.value)} required />
              </div>
              <div className="field">
                <label>Configured node count (1–127)</label>
                <input value={nodeCount} onChange={(e) => setNodeCount(e.target.value)} required />
              </div>
            </div>
            <div className="split">
              <div className="field">
                <label>Serial number (optional)</label>
                <input value={serialNumber} onChange={(e) => setSerialNumber(e.target.value)} />
              </div>
              <div className="field">
                <label>Vendor device ID (optional)</label>
                <input value={vendorDeviceId} onChange={(e) => setVendorDeviceId(e.target.value)} />
              </div>
            </div>
            <div className="split">
              <div className="field">
                <label>Node rows (optional)</label>
                <input value={nodeRows} onChange={(e) => setNodeRows(e.target.value)} />
              </div>
              <div className="field">
                <label>Nodes per row (optional)</label>
                <input value={nodesPerRow} onChange={(e) => setNodesPerRow(e.target.value)} />
              </div>
            </div>
            <div className="split">
              <div className="field">
                <label>Latitude (optional)</label>
                <input value={latitude} onChange={(e) => setLatitude(e.target.value)} />
              </div>
              <div className="field">
                <label>Longitude (optional)</label>
                <input value={longitude} onChange={(e) => setLongitude(e.target.value)} />
              </div>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                {editingTerminal ? 'Save changes' : 'Register & show pairing code'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {settingsTerminal && (
        <div className="dialog-backdrop">
          <div className="dialog dialog-wide">
            <h2>Cabinet settings — {settingsTerminal.name}</h2>
            <CabinetSettingsPanel
              terminal={settingsTerminal}
              unitName={siteName(settingsTerminal.siteId)}
            />
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => setSettingsTerminal(null)}>
                Close
              </Button>
            </div>
          </div>
        </div>
      )}

      {dialog}
    </section>
  )
}
