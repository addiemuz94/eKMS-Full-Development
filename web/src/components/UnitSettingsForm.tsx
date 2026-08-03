import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto, TerminalDto } from '../api/types'
import { Button, LinearProgress } from './ui'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'

type Props = {
  siteId: string
  /** When set (Cabinet Management Location tab), also edit map coordinates on the cabinet. */
  terminal?: TerminalDto
  embedded?: boolean
  onSaved?: (site: SiteDto) => void
  onCabinetSaved?: (terminal: TerminalDto) => void
}

/**
 * Edit a single unit (site) — used inside cabinet Settings. Creating/recycling other
 * units stays on Registration or a dedicated flow; this is the cabinet’s own unit.
 */
export function UnitSettingsForm({
  siteId,
  terminal,
  embedded = false,
  onSaved,
  onCabinetSaved,
}: Props) {
  const [site, setSite] = useState<SiteDto | null>(null)
  const [liveTerminal, setLiveTerminal] = useState<TerminalDto | null>(terminal ?? null)
  const [name, setName] = useState('')
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')
  const [latitude, setLatitude] = useState(
    terminal?.latitude != null ? String(terminal.latitude) : '',
  )
  const [longitude, setLongitude] = useState(
    terminal?.longitude != null ? String(terminal.longitude) : '',
  )
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const cityOptions = useMemo(() => citiesForState(province), [province])

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const rows = await api.listSites()
      const current = rows.find((row) => row.id === siteId) ?? null
      setSite(current)
      if (current) {
        setName(current.name)
        setProvince(current.province ?? '')
        setCity(current.city ?? '')
      } else {
        setError('This cabinet’s location was not found (it may have been moved to deleted items).')
      }
      if (terminal) {
        const terms = await api.listTerminals()
        const t = terms.find((row) => row.id === terminal.id) ?? null
        setLiveTerminal(t)
        if (t) {
          setLatitude(t.latitude != null ? String(t.latitude) : '')
          setLongitude(t.longitude != null ? String(t.longitude) : '')
        }
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load location')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [siteId, terminal?.id])

  useEffect(() => {
    if (!terminal) return
    setLiveTerminal(terminal)
    setLatitude(terminal.latitude != null ? String(terminal.latitude) : '')
    setLongitude(terminal.longitude != null ? String(terminal.longitude) : '')
  }, [terminal?.id, terminal?.revision, terminal?.latitude, terminal?.longitude])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!site) return
    if (!name.trim()) {
      setError('Location name is required.')
      return
    }
    if (!province) {
      setError('Select a Malaysian state / province.')
      return
    }
    if (!city) {
      setError('Select a city.')
      return
    }
    setBusy(true)
    setError(null)
    setNotice(null)
    try {
      const updated = await api.updateSite(site.id, {
        name: name.trim(),
        province,
        city,
        expectedRevision: site.revision,
      })
      setSite(updated)
      if (liveTerminal) {
        const updatedTerminal = await api.updateTerminal(liveTerminal.id, {
          siteId: liveTerminal.siteId,
          name: liveTerminal.name,
          boxAddress: liveTerminal.boxAddress,
          serialNumber: liveTerminal.serialNumber ?? null,
          configuredSlotCount: liveTerminal.configuredSlotCount,
          vendorDeviceId: liveTerminal.vendorDeviceId ?? null,
          nodeRows: liveTerminal.nodeRows ?? null,
          nodesPerRow: liveTerminal.nodesPerRow ?? null,
          latitude: latitude.trim() ? Number(latitude) : null,
          longitude: longitude.trim() ? Number(longitude) : null,
          expectedRevision: liveTerminal.revision,
        })
        setLiveTerminal(updatedTerminal)
        onCabinetSaved?.(updatedTerminal)
      }
      setNotice('Location saved.')
      onSaved?.(updated)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This location was changed by someone else. Reloading — please reapply.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save location')
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
            <h3 style={{ margin: 0 }}>Location Settings</h3>
            <p className="muted">
              Name and place details for this cabinet’s location
              {terminal ? ', including map coordinates' : ''}.
            </p>
          </div>
        </div>
      )}

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && !site && <LinearProgress className="table-busy" label="Loading location" />}

      {site && (
        <form onSubmit={onSave}>
          <div className="field">
            <label>Location name</label>
            <input value={name} onChange={(e) => setName(e.target.value)} required />
          </div>
          <div className="field">
            <label>State / province (Malaysia)</label>
            <select
              value={province}
              required
              onChange={(e) => {
                setProvince(e.target.value)
                setCity('')
              }}
            >
              <option value="">Select state…</option>
              {MALAYSIA_STATES.map((state) => (
                <option key={state.name} value={state.name}>
                  {state.name}
                </option>
              ))}
            </select>
          </div>
          <div className="field">
            <label>City</label>
            <select value={city} required onChange={(e) => setCity(e.target.value)} disabled={!province}>
              <option value="">{province ? 'Select city…' : 'Select a state first'}</option>
              {cityOptions.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </div>
          {terminal && (
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
          )}
          <div className="dialog-actions" style={{ marginTop: 12 }}>
            <Button type="submit" icon={Check} loading={busy}>
              Save location
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
