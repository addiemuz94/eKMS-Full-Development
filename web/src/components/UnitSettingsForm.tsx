import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { Button, LinearProgress } from './ui'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'

type Props = {
  siteId: string
  embedded?: boolean
  onSaved?: (site: SiteDto) => void
}

/**
 * Edit a single unit (site) — used inside cabinet Settings. Creating/recycling other
 * units stays on Registration or a dedicated flow; this is the cabinet’s own unit.
 */
export function UnitSettingsForm({ siteId, embedded = false, onSaved }: Props) {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [site, setSite] = useState<SiteDto | null>(null)
  const [name, setName] = useState('')
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')
  const [parentSiteId, setParentSiteId] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const cityOptions = useMemo(() => citiesForState(province), [province])
  const parentOptions = useMemo(
    () => sites.filter((candidate) => candidate.id !== siteId),
    [sites, siteId],
  )

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const rows = await api.listSites()
      setSites(rows)
      const current = rows.find((row) => row.id === siteId) ?? null
      setSite(current)
      if (current) {
        setName(current.name)
        setProvince(current.province ?? '')
        setCity(current.city ?? '')
        setParentSiteId(current.parentSiteId ?? '')
      } else {
        setError('This cabinet’s unit was not found (it may have been recycled).')
      }
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load unit')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [siteId])

  async function onSave(e: FormEvent) {
    e.preventDefault()
    if (!site) return
    if (!name.trim()) {
      setError('Unit name is required.')
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
        parentSiteId: parentSiteId || null,
        expectedRevision: site.revision,
      })
      setSite(updated)
      setNotice('Unit saved.')
      onSaved?.(updated)
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError('This unit was changed by someone else. Reloading — please reapply.')
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save unit')
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
            <h3 style={{ margin: 0 }}>Unit Settings</h3>
            <p className="muted">Name, location, and superior unit for this cabinet’s unit.</p>
          </div>
        </div>
      )}

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}
      {busy && !site && <LinearProgress className="table-busy" label="Loading unit" />}

      {site && (
        <form onSubmit={onSave}>
          <div className="field">
            <label>Unit name</label>
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
          <div className="field">
            <label>Superior unit (optional)</label>
            <select value={parentSiteId} onChange={(e) => setParentSiteId(e.target.value)}>
              <option value="">— None —</option>
              {parentOptions.map((candidate) => (
                <option key={candidate.id} value={candidate.id}>
                  {candidate.name}
                </option>
              ))}
            </select>
          </div>
          <div className="dialog-actions" style={{ marginTop: 12 }}>
            <Button type="submit" icon={Check} loading={busy}>
              Save unit
            </Button>
          </div>
        </form>
      )}
    </section>
  )
}
