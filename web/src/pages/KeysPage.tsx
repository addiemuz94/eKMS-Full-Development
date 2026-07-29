import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { Check, Plus, X } from 'lucide-react'
import { api, ApiError } from '../api/client'
import type { KeyDto, SiteDto } from '../api/types'
import { Button, LinearProgress, useConfirm } from '../components/ui'

type SortDir = 'asc' | 'desc'

export function KeysPage() {
  const { confirmAction, dialog } = useConfirm()
  const [keys, setKeys] = useState<KeyDto[]>([])
  const [sites, setSites] = useState<SiteDto[]>([])
  const [query, setQuery] = useState('')
  const [siteFilter, setSiteFilter] = useState('all')
  const [enrollFilter, setEnrollFilter] = useState<'all' | 'enrolled' | 'not-enrolled'>('all')
  const [sortDir, setSortDir] = useState<SortDir>('asc')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [open, setOpen] = useState(false)
  const [editingKey, setEditingKey] = useState<KeyDto | null>(null)
  const [displayName, setDisplayName] = useState('')
  const [siteId, setSiteId] = useState('')

  async function reload() {
    setBusy(true)
    setError(null)
    try {
      const [keyRows, siteRows] = await Promise.all([api.listKeys(), api.listSites()])
      setKeys(keyRows)
      setSites(siteRows)
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

  const filtered = useMemo(() => {
    const q = query.trim().toLowerCase()
    return keys
      .filter((k) => {
        const sn = sites.find((s) => s.id === k.siteId)?.name ?? ''
        const matchQ = !q || k.displayName.toLowerCase().includes(q) || sn.toLowerCase().includes(q)
        const matchSite = siteFilter === 'all' || k.siteId === siteFilter
        const enrolled = Boolean(k.fobEnrollmentReference)
        const matchEnroll = enrollFilter === 'all' || (enrollFilter === 'enrolled' ? enrolled : !enrolled)
        return matchQ && matchSite && matchEnroll
      })
      .sort((a, b) =>
        sortDir === 'asc'
          ? a.displayName.localeCompare(b.displayName)
          : b.displayName.localeCompare(a.displayName),
      )
  }, [keys, sites, query, siteFilter, enrollFilter, sortDir])

  function openEdit(key: KeyDto) {
    setEditingKey(key)
    setDisplayName(key.displayName)
    setSiteId(key.siteId)
    setError(null)
    setOpen(true)
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
        await api.createKey({ siteId, displayName: displayName.trim() })
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
          <p className="muted">Managed keys from the backend. Raw NFC UIDs never appear here.</p>
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
        <select value={siteFilter} onChange={(e) => setSiteFilter(e.target.value)} title="Filter by unit">
          <option value="all">All units</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>{s.name}</option>
          ))}
        </select>
        <select value={enrollFilter} onChange={(e) => setEnrollFilter(e.target.value as 'all' | 'enrolled' | 'not-enrolled')} title="Filter by enrollment">
          <option value="all">All enrollment</option>
          <option value="enrolled">Enrolled</option>
          <option value="not-enrolled">Not enrolled</option>
        </select>
        <Button variant="outlined" onClick={() => setSortDir((d) => d === 'asc' ? 'desc' : 'asc')}>
          Name {sortDir === 'asc' ? '↑' : '↓'}
        </Button>
      </div>

      {filtered.length ? (
        <div className="data-panel">
          <table className="data-table">
            <thead>
              <tr>
                <th>Key</th>
                <th>Unit</th>
                <th>Enrollment</th>
                <th className="col-actions">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.map((key) => (
                <tr key={key.id}>
                  <td className="cell-title">{key.displayName}</td>
                  <td>{sites.find((s) => s.id === key.siteId)?.name ?? '—'}</td>
                  <td>
                    {key.fobEnrollmentReference
                      ? <span className="badge badge-success">Enrolled</span>
                      : <span className="muted">Not enrolled</span>}
                  </td>
                  <td className="col-actions">
                    <div className="row-actions">
                      <Button variant="link" onClick={() => openEdit(key)}>Edit</Button>
                      <Button
                        variant="link"
                        onClick={() =>
                          void (async () => {
                            if (!(await confirmAction({ message: 'Move key to Recycle Bin?', danger: true }))) return
                            await api.deleteKey(key.id)
                            await reload()
                          })()
                        }
                      >
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
            <p className="dialog-copy">Create a managed key record without exposing NFC secrets or biometric material.</p>
            <div className="field">
              <label>Key name</label>
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} required />
            </div>
            <div className="field">
              <label>Unit</label>
              <select value={siteId} onChange={(e) => setSiteId(e.target.value)} required>
                {sites.map((s) => (
                  <option key={s.id} value={s.id}>{s.name}</option>
                ))}
              </select>
            </div>
            <div className="dialog-actions">
              <Button variant="outlined" icon={X} onClick={() => { setOpen(false); setEditingKey(null) }}>Cancel</Button>
              <Button type="submit" icon={Check} loading={busy}>{editingKey ? 'Save changes' : 'Save'}</Button>
            </div>
          </form>
        </div>
      )}

      {dialog}
    </section>
  )
}
