import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { SiteDto } from '../api/types'
import { Button, CircularProgress, LinearProgress, SegmentedControl, useConfirm } from '../components/ui'
import { MALAYSIA_STATES, citiesForState } from '../geo/malaysiaLocations'

type UnitView = 'all' | 'mapped'

export function UnitsPage() {
  const { confirmAction, dialog } = useConfirm()
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [view, setView] = useState<UnitView>('all')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingSite, setEditingSite] = useState<SiteDto | null>(null)
  const [name, setName] = useState('')
  const [province, setProvince] = useState('')
  const [city, setCity] = useState('')

  const cityOptions = useMemo(() => citiesForState(province), [province])

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      setSites(await api.listSites())
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to load locations')
    } finally {
      setBusy(false)
    }
  }

  useEffect(() => {
    void reload()
  }, [])

  const [stateFilter, setStateFilter] = useState('')
  const [sortKey, setSortKey] = useState<'name' | 'province' | 'city'>('name')
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc')

  const usedStates = MALAYSIA_STATES.map((s) => s.name)

  function toggleSort(k: 'name' | 'province' | 'city') {
    if (sortKey === k) setSortDir((d) => (d === 'asc' ? 'desc' : 'asc'))
    else { setSortKey(k); setSortDir('asc') }
  }
  const arrow = (k: 'name' | 'province' | 'city') => sortKey === k ? (sortDir === 'asc' ? ' ↑' : ' ↓') : ''

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return sites
      .filter((site) => {
        const matchesQuery =
          !q ||
          site.name.toLowerCase().includes(q) ||
          (site.province ?? '').toLowerCase().includes(q) ||
          (site.city ?? '').toLowerCase().includes(q)
        const hasLocation = Boolean(site.province?.trim() && site.city?.trim())
        const matchesView = view === 'all' || hasLocation
        const matchesState = !stateFilter || (site.province ?? '') === stateFilter
        return matchesQuery && matchesView && matchesState
      })
      .sort((a, b) => {
        const av = (sortKey === 'name' ? a.name : sortKey === 'province' ? (a.province ?? '') : (a.city ?? '')).toLowerCase()
        const bv = (sortKey === 'name' ? b.name : sortKey === 'province' ? (b.province ?? '') : (b.city ?? '')).toLowerCase()
        return sortDir === 'asc' ? av.localeCompare(bv) : bv.localeCompare(av)
      })
  }, [sites, query, view, stateFilter, sortKey, sortDir])

  function resetForm() {
    setName('')
    setProvince('')
    setCity('')
  }

  function openEdit(site: SiteDto) {
    setEditingSite(site)
    setName(site.name)
    setProvince(site.province ?? '')
    setCity(site.city ?? '')
    setError(null)
    setOpen(true)
  }

  async function onSave(e: FormEvent) {
    e.preventDefault()
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
    try {
      if (editingSite) {
        await api.updateSite(editingSite.id, {
          name: name.trim(),
          province,
          city,
          expectedRevision: editingSite.revision,
        })
        setNotice('Location saved.')
      } else {
        await api.createSite({
          name: name.trim(),
          province,
          city,
        })
        setNotice('Location saved.')
      }
      setOpen(false)
      setEditingSite(null)
      resetForm()
      await reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 409) {
        setError(
          'This location was changed by someone else since you opened it. Reloading the latest version — please reapply your edit.',
        )
        setOpen(false)
        setEditingSite(null)
        await reload()
      } else {
        setError(err instanceof ApiError ? err.message : 'Failed to save location')
      }
    } finally {
      setBusy(false)
    }
  }

  async function onArchive(id: string) {
    if (!(await confirmAction({ message: 'Delete this location? It will move to Deleted items and can be restored for 60 days.', danger: true }))) return
    setBusy(true)
    setError(null)
    try {
      await api.deleteSite(id)
      setNotice('Location deleted.')
      await reload()
    } catch (err) {
      setError(err instanceof ApiError ? err.message : 'Failed to archive location')
    } finally {
      setBusy(false)
    }
  }


  return (
    <section>
      <div className="page-header">
        <div>
          <h1>Location Settings</h1>
          <p className="muted">
            Create the organisation hierarchy used by cabinets, personnel, keys, permissions and reports.
          </p>
        </div>
        <Button
          icon={Plus}
          onClick={() => {
            resetForm()
            setEditingSite(null)
            setOpen(true)
          }}
        >
          Add location
        </Button>
      </div>

      {notice && <div className="notice">{notice}</div>}
      {error && <div className="error-banner">{error}</div>}

      {busy && <LinearProgress className="table-busy" label="Loading locations" />}

      <div className="toolbar-row">
        <input
          className="search"
          placeholder="Search location, state, or city"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          style={{ flex: 1 }}
        />
        <select value={stateFilter} onChange={(e) => setStateFilter(e.target.value)} title="Filter by state">
          <option value="">All states</option>
          {usedStates.map((s) => (
            <option key={s} value={s}>{s}</option>
          ))}
        </select>
        <SegmentedControl
          ariaLabel="Location filter"
          value={view}
          onChange={setView}
          options={[
            { value: 'all', label: 'All' },
            { value: 'mapped', label: 'Mapped' },
          ]}
        />
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('name')}>Location{arrow('name')}</th>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('province')}>State / province{arrow('province')}</th>
                <th style={{ cursor: 'pointer' }} onClick={() => toggleSort('city')}>City{arrow('city')}</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((site) => (
                <tr key={site.id}>
                  <td className="cell-title">{site.name}</td>
                  <td>{site.province?.trim() || '—'}</td>
                  <td>{site.city?.trim() || '—'}</td>
                  <td className="col-actions">
                    <Button variant="link" onClick={() => openEdit(site)}>
                      Edit
                    </Button>
                    <Button variant="link" onClick={() => void onArchive(site.id)}>
                      Delete
                    </Button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      ) : (
        !busy && <div className="empty-state">No active location matches the current search.</div>
      )}

      {open && (
        <div className="dialog-backdrop">
          <form className="dialog" onSubmit={onSave}>
            <h2>{editingSite ? 'Edit location' : 'Add location'}</h2>
            <p className="dialog-copy">
              Choose a Malaysian state/province and city so the location appears correctly on the dashboard map.
            </p>
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
            <div className="dialog-actions">
              <Button
                variant="outlined"
                icon={X}
                onClick={() => {
                  setOpen(false)
                  setEditingSite(null)
                }}
              >
                Cancel
              </Button>
              <Button type="submit" icon={Check} loading={busy}>
                {editingSite ? 'Save changes' : 'Save location'}
              </Button>
            </div>
          </form>
        </div>
      )}

      {busy && !open && !filtered.length && (
        <div className="busy-inline" style={{ marginTop: 16 }}>
          <CircularProgress size={22} />
          Loading locations…
        </div>
      )}

      {dialog}
    </section>
  )
}
