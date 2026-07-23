import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'

export function UnitsPage() {
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [name, setName] = useState('')
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')
  const [parentSiteId, setParentSiteId] = useState('')

  const cityOptions = useMemo(() => citiesForState(province), [province])

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setSites(await api.listSites())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load units')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    if (!q) return sites
    return sites.filter((site) => {
      const parent = sites.find((candidate) => candidate.id === site.parentSiteId)?.name ?? ''
      return (
        site.name.toLowerCase().includes(q) ||
        (site.province ?? '').toLowerCase().includes(q) ||
        (site.city ?? '').toLowerCase().includes(q) ||
        parent.toLowerCase().includes(q)
      )
    })
  }, [sites, query])

  function resetForm() {
    setName('')
    setProvince('')
    setCity('')
    setParentSiteId('')
  }

  async function onCreate(e: FormEvent) {
    e.preventDefault()
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
    try {
      await api.createSite({
        name: name.trim(),
        province,
        city,
        parentSiteId: parentSiteId || null,
      })
      setOpen(false)
      resetForm()
      setNotice('Unit saved.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to create unit')
    } finally {
      setBusy(false)
    }
  }

  async function onArchive(id: string) {
    if (!confirm('Move this unit to the Recycle Bin?')) return
    setBusy(true)
    setError(null)
    try {
      await api.deleteSite(id)
      setNotice('Unit moved to Recycle Bin.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to archive unit')
    } finally {
      setBusy(false)
    }
  }

  function parentName(site: SiteDto) {
    if (!site.parentSiteId) return '—'
    return sites.find((candidate) => candidate.id === site.parentSiteId)?.name ?? '—'
  }

  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Unit Settings</h1>
          <p className="muted">
            Create the organisation hierarchy used by terminals, personnel, keys, permissions and reports.
          </p>
        </div>
        <button
          className="btn"
          type="button"
          onClick={() => {
            resetForm()
            setOpen(true)
          }}
        >
          Add unit
        </button>
      </div>

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}

      <input
        className="search"
        placeholder="Search unit, state, city or superior unit"
        value={query}
        onChange={(e) => setQuery(e.target.value)}
      />

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Unit</th>
                <th>State / province</th>
                <th>City</th>
                <th>Superior unit</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((site) => (
                <tr key={site.id}>
                  <td className="cell-title">{site.name}</td>
                  <td>{site.province?.trim() || '—'}</td>
                  <td>{site.city?.trim() || '—'}</td>
                  <td>{parentName(site)}</td>
                  <td className="col-actions">
                    <button className="btn linkish" type="button" onClick={() => void onArchive(site.id)}>
                      Recycle
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No active unit matches the current search.</div>
      )}

      {open && (
        <div className="dialog-backdrop" onClick={() => setOpen(false)}>
          <form className="dialog" onClick={(e) => e.stopPropagation()} onSubmit={onCreate}>
            <h2>Add unit</h2>
            <p className="dialog-copy">
              Choose a Malaysian state/province and city so the unit appears correctly on the dashboard map.
            </p>
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
                <option value="">Select state / province</option>
                {MALAYSIA_STATES.map((state) => (
                  <option key={state.id} value={state.name}>
                    {state.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="field">
              <label>City</label>
              <select value={city} required disabled={!province} onChange={(e) => setCity(e.target.value)}>
                <option value="">{province ? 'Select city' : 'Select a state first'}</option>
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
                {sites.map((site) => (
                  <option key={site.id} value={site.id}>
                    {site.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <button className="btn secondary" type="button" onClick={() => setOpen(false)}>
                Cancel
              </button>
              <button className="btn" type="submit" disabled={busy}>
                Save unit
              </button>
            </div>
          </form>
        </div>
      )}
    </section>
  )
}
