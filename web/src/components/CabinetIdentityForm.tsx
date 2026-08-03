import { useEffect, useState, type FormEvent } from 'react'
import { Check } from 'lucide-react'
import { api, ApiError } from '../api/client'
import { layoutFieldsFromTerminal, parseCabinetLayout } from '../api/cabinetLayout'
import type { SiteDto, TerminalDto } from '../api/types'
import { CabinetLayoutFields } from './CabinetLayoutFields'
import { Button, LinearProgress } from './ui'

type Props = {
  terminal: TerminalDto
  embedded?: boolean
  onSaved?: (terminal: TerminalDto) => void
}

/**
 * Inline cabinet configuration (formerly the Edit dialog on Cabinet Management).
 * Map coordinates live under the Location tab.
 */
export function CabinetIdentityForm({ terminal, embedded = false, onSaved }: Props) {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [live, setLive] = useState(terminal)
  const [name, setName] = useState(terminal.name)
  const [siteId, setSiteId] = useState(terminal.siteId)
  const [boxAddress, setBoxAddress] = useState(String(terminal.boxAddress))
  const initialLayout = layoutFieldsFromTerminal(terminal)
  const [nodeRows, setNodeRows] = useState(initialLayout.rows)
  const [nodesPerRow, setNodesPerRow] = useState(initialLayout.columns)
  const [vendorDeviceId, setVendorDeviceId] = useState(terminal.vendorDeviceId ?? '')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  function applyTerminal(t: TerminalDto) {
    setLive(t)
    setName(t.name)
    setSiteId(t.siteId)
    setBoxAddress(String(t.boxAddress))
    const layout = layoutFieldsFromTerminal(t)
    setNodeRows(layout.rows)
    setNodesPerRow(layout.columns)
    setVendorDeviceId(t.vendorDeviceId ?? '')
  }

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [siteRows, terminalRows] = await Promise.all([api.listSites(), api.listTerminals()])
      setSites(siteRows)
      const current = terminalRows.find((row) => row.id === terminal.id)
      if (current) {
        applyTerminal(current)
      } else {
        setError('This cabinet was not found (it may have been moved to deleted items).')
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load cabinet')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    applyTerminal(terminal)
    void reload()
  }, [terminal.id])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) {
      setError('Cabinet name is required.')
      return
    }
    if (!siteId) {
      setError('Select a location for this cabinet.')
      return
    }
    const layout = parseCabinetLayout(nodeRows, nodesPerRow)
    if (!layout.ok) {
      setError(layout.message)
      return
    }
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.updateTerminal(live.id, {
        siteId,
        name: name.trim(),
        boxAddress: Math.max(1, Number(boxAddress) || 1),
        serialNumber: live.serialNumber ?? null,
        configuredSlotCount: layout.value.totalSlots,
        vendorDeviceId: vendorDeviceId.trim() || null,
        nodeRows: layout.value.rows,
        nodesPerRow: layout.value.columns,
        latitude: live.latitude ?? null,
        longitude: live.longitude ?? null,
        expectedRevision: live.revision,
      })
      applyTerminal(updated)
      setNotice('Cabinet saved.')
      onSaved?.(updated)
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This cabinet was changed by someone else since you opened it. Reloading — please reapply.',
        )
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save cabinet')
      }
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className={embedded ? 'resource-embedded' : undefined}>
      {embedded && (
        <div className="embedded-header">
          <div>
            <h3 style={{ margin: 0 }}>Cabinet settings</h3>
            <p className="muted">Name, location assignment, and layout (rows × columns).</p>
          </div>
        </div>
      )}

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && sites.length === 0 && <LinearProgress className="table-busy" label="Loading cabinet" />}

      <form onSubmit={onSave}>
        <div className="field">
          <label>Cabinet name</label>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            required
            placeholder="Johor HQ Cabinet"
          />
        </div>
        <div className="field">
          <label>Location</label>
          <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
            {sites.map((site) => (
              <option key={site.id} value={site.id}>
                {site.name}
                {site.province ? ` (${site.province})` : ''}
              </option>
            ))}
          </select>
        </div>
        <div className="field">
          <label>Cabinet number</label>
          <input value={boxAddress} onChange={(e) => setBoxAddress(e.target.value)} required />
        </div>
        <CabinetLayoutFields
          rows={nodeRows}
          columns={nodesPerRow}
          onRowsChange={setNodeRows}
          onColumnsChange={setNodesPerRow}
        />
        <div className="field">
          <label>Vendor device ID (optional)</label>
          <input value={vendorDeviceId} onChange={(e) => setVendorDeviceId(e.target.value)} />
        </div>
        <div className="dialog-actions" style={{ marginTop: 12 }}>
          <Button type="submit" icon={Check} loading={busy}>
            Save cabinet
          </Button>
        </div>
      </form>
    </section>
  )
}
